//! About (`AboutActivity`, DESIGN §7.19): the app mark, the name, the version, the tagline, the three links,
//! the licence and the independence sentence. A link opens in the browser.

use crate::glass;
use crate::phone;
use crate::{icons, palette, t, App};
use eframe::egui::{self, Align2, FontId, Pos2, Rect, Ui, Vec2};

const GITHUB: &str = "https://github.com/spizganed/QuickBuds";
const KOFI: &str = "https://ko-fi.com/spizganed";
const DISCORD: &str = "https://discord.gg/pvsnEf9TrZ";
/// DESIGN.md, "Design basis of each style".
const INDEPENDENT: &str = "QuickBuds is an independent project and is not affiliated with or endorsed by Google or Apple.";

pub fn show(app: &App, ui: &mut Ui, body: Rect) -> f32 {
    let tr = &app.tr;
    let w = phone::content(body);
    let x0 = body.left();
    let mid = x0 + w / 2.0;
    let accent = palette::active(palette::is_light_system(ui.ctx()), false).accent;
    let mut y = body.top();

    // The app mark in a 120 dp pill, then the name, the version and the tagline, all centred.
    ui.painter().circle_filled(Pos2::new(mid, y + 60.0), 60.0, glass::white(0.09));
    let tint = egui::Color32::from_rgba_unmultiplied(accent.r(), accent.g(), accent.b(), 235);
    glass::icon_at(ui, icons::LAUNCHER_FOREGROUND, Rect::from_center_size(Pos2::new(mid, y + 60.0), Vec2::splat(74.0)), tint);
    y += 120.0;
    y += 10.0;
    ui.painter().text(Pos2::new(mid, y + 17.0), Align2::CENTER_CENTER, t(tr, "app_name"), FontId::proportional(24.0), glass::white(1.0));
    y += 34.0;
    let version = t(tr, "about_version").replace("%1$s", env!("CARGO_PKG_VERSION"));
    ui.painter().text(Pos2::new(mid, y + 10.0), Align2::CENTER_CENTER, &version, FontId::proportional(14.0), glass::white(0.74));
    y += 20.2;
    let tagline = t(tr, "about_tagline");
    let h = phone::wrap_height(ui, tagline, 15.0, w - 16.0);
    phone::paragraph(ui, Rect::from_min_size(Pos2::new(x0 + 8.0, y + 14.0), Vec2::new(w - 16.0, h)), tagline, 15.0, glass::white(1.0), true);
    y += 14.0 + h + 20.0;

    // The three links: the phone's buttons carry the description; here the address takes its place.
    let links = [
        (t(tr, "about_github"), GITHUB, icons::GITHUB),
        (t(tr, "about_kofi"), KOFI, icons::KOFI),
        (t(tr, "about_discord"), DISCORD, icons::CHAT),
    ];
    for (label, url, icon) in links {
        let card = Rect::from_min_size(Pos2::new(x0, y), Vec2::new(w, 60.0));
        let hit = ui.interact(card, ui.scope_id().with(("about-link", url)), egui::Sense::click());
        if hit.hovered() { ui.ctx().set_cursor_icon(egui::CursorIcon::PointingHand); }
        if hit.clicked() { ui.ctx().open_url(egui::OpenUrl::new_tab(url)); }
        glass::flat_at(ui, card, 18.0, if hit.hovered() { 1.4 } else { 1.0 });
        glass::icon_at(ui, icon, Rect::from_center_size(Pos2::new(x0 + 27.0, card.center().y), Vec2::splat(22.0)), glass::white(0.9));
        glass::text(ui, Pos2::new(x0 + 50.0, card.center().y - 9.0), Align2::LEFT_CENTER, label, 15.0, 1.0);
        glass::text(ui, Pos2::new(x0 + 50.0, card.center().y + 11.0), Align2::LEFT_CENTER, url, 12.0, 0.6);
        y += 60.0 + 10.0;
    }
    y += 8.0;

    // The licence.
    phone::label(ui, x0, y, t(tr, "about_license_title"), accent);
    y += phone::SECTION;
    let body_text = t(tr, "about_license_body");
    y += card_text(ui, Rect::from_min_size(Pos2::new(x0, y), Vec2::new(w, 0.0)), body_text, w) + 8.0;

    // Why the app names other companies.
    y += card_text(ui, Rect::from_min_size(Pos2::new(x0, y), Vec2::new(w, 0.0)), INDEPENDENT, w);
    y + phone::GAP
}

/// A card of one 14 sp secondary paragraph. The rect's height is measured here. Returns the height.
fn card_text(ui: &mut Ui, rect: Rect, text: &str, w: f32) -> f32 {
    let inner = w - 2.0 * phone::MARGIN;
    let h = phone::wrap_height(ui, text, 14.0, inner);
    let card = Rect::from_min_size(rect.min, Vec2::new(w, h + 28.0));
    glass::flat_at(ui, card, phone::RADIUS, 1.0);
    phone::paragraph(ui, Rect::from_min_size(Pos2::new(card.left() + phone::MARGIN, card.top() + 14.0), Vec2::new(inner, h)), text, 14.0, glass::white(0.74), false);
    card.height()
}
