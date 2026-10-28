//! Home (DESIGN §7.1) at the phone's sizes, measured from a developer capture of the Glass home screen:
//! px / 2.625 = dp. One 379.4 dp column, a 213.7 dp battery card, a 72 dp noise control and 59.8 dp
//! feature rows 8 dp apart.

use crate::glass::{self, white, Trailing};
use crate::home_logic;
use crate::icons;
use crate::session::{Cmd, Snapshot, Status};
use crate::types::{self, FEATURE_HEARING, FEATURE_HIRES, FEATURE_SPATIAL, FEATURE_WIND_NOISE};
use crate::{t, App, Page};
use eframe::egui::{self, Align2, Color32, FontId, Pos2, Rect, Ui, Vec2};

/// The phone's content column (DESIGN §2.1).
pub const COLUMN: f32 = 379.4;
pub const MARGIN: f32 = 16.0;
pub const HEADER: f32 = 48.0;
/// The gap between two Home cards (DESIGN §2.1).
pub const GAP: f32 = 12.0;
pub const BATTERY: f32 = 213.7;
pub const NOISE: f32 = 72.0;
pub const ROW: f32 = 59.8;
pub const ROW_GAP: f32 = 8.0;
pub const RADIUS_CARD: f32 = 24.0;
pub const RADIUS_ROW: f32 = 16.0;

/// Verticals inside the battery card, from its top edge.
const SLOT_TOP: f32 = 16.0;
const RING_R: f32 = 45.0;
const RING_TOP: f32 = 4.0;
const PCT_Y: f32 = 131.0;
const WEAR_Y: f32 = 155.4;
const NAME_Y: f32 = 188.8;
/// A wrapped subtitle line adds this to a row.
const ROW_LINE: f32 = 15.6;

const PAD: f32 = 16.0;
const ICON: f32 = 24.0;
/// The text column starts after the 24 dp icon and its 14 dp margin.
const TEXT_X: f32 = 54.0;
/// The trailing slot: 52 dp for a switch, 24 for a chevron, plus the 16 dp end padding.
fn trail_width(trail: &Trailing) -> f32 {
    if matches!(trail, Trailing::Chevron) { 40.0 } else { 68.0 }
}
const SUB_TOP: f32 = 31.2;
const TITLE_CY: f32 = 19.6;
/// Glass secondary text: the `textSecondary` token, #B8FFFFFF (DESIGN §2.5).
const SECONDARY: f32 = 0.72;

/// The one phone column, centred in a wider window.
pub fn column(body: Rect) -> Rect {
    let w = COLUMN.min(body.width() - 2.0 * MARGIN);
    Rect::from_min_size(Pos2::new(body.center().x - w / 2.0, body.top()), Vec2::new(w, body.height()))
}

fn mix(a: Color32, b: Color32, t: f32) -> Color32 {
    let m = |x: u8, y: u8| (x as f32 + (y as f32 - x as f32) * t) as u8;
    Color32::from_rgba_unmultiplied(m(a.r(), b.r()), m(a.g(), b.g()), m(a.b(), b.b()), m(a.a(), b.a()))
}

/// Cuts a line with an ellipsis when it is wider than `width`.
fn fit(ui: &Ui, s: &str, size: f32, width: f32) -> String {
    let font = FontId::proportional(size);
    let measure = |text: &str| ui.painter().layout_no_wrap(text.to_string(), font.clone(), Color32::WHITE).size().x;
    if measure(s) <= width {
        return s.to_string();
    }
    let mut cut = s.to_string();
    while !cut.is_empty() {
        cut.pop();
        let shown = format!("{}…", cut.trim_end());
        if measure(&shown) <= width {
            return shown;
        }
    }
    String::new()
}

fn menu_icon(ui: &Ui, c: Pos2, colour: Color32) {
    let st = egui::Stroke::new(2.6, colour);
    for dy in [-6.0, 0.0, 6.0] {
        ui.painter().line_segment([c + Vec2::new(-7.0, dy), c + Vec2::new(7.0, dy)], st);
    }
}

/// The header: model, connection pill, an empty title, dev tools, settings. The close button is the
/// desktop's own; the phone has none.
pub fn header(app: &mut App, ui: &mut Ui, content: Rect) {
    let c = column(content);
    let x0 = c.left();
    let top = content.top();

    let mut x = x0;
    let model = Rect::from_min_size(Pos2::new(x, top), Vec2::splat(44.0));
    if glass::button_at(ui, "model", model, 22.0, false).clicked() {
        app.page = Page::Models;
    }
    menu_icon(ui, model.center(), white(0.9));
    x += 44.0 + 8.0;

    let label = match app.snap.status {
        Status::On => t(&app.tr, "conn_action_disconnect"),
        Status::Connecting => t(&app.tr, "conn_connecting"),
        Status::Off => t(&app.tr, "conn_action_connect"),
    };
    let tw = ui.painter().layout_no_wrap(label.to_string(), FontId::proportional(14.0), Color32::WHITE).size().x;
    let w = (PAD + 12.0 + 8.0 + tw + 18.0).max(129.5);
    let pill = Rect::from_min_size(Pos2::new(x, top), Vec2::new(w, 44.0));
    if glass::button_at(ui, "conn", pill, 22.0, false).clicked() {
        if app.snap.status == Status::On { app.disconnect_ask = true; } else { app.send(Cmd::Connect); }
    }
    let dot = match app.snap.status {
        Status::On => Color32::from_rgb(0x5B, 0xE4, 0x9B),
        Status::Connecting => Color32::from_rgb(0xFF, 0xC8, 0x57),
        Status::Off => white(SECONDARY),
    };
    ui.painter().circle_filled(Pos2::new(pill.left() + 22.0, pill.center().y), 6.0, dot);
    let tone = if app.snap.status == Status::On { 1.0 } else { SECONDARY };
    glass::text(ui, Pos2::new(pill.left() + 36.0, pill.center().y), Align2::LEFT_CENTER, label, 14.0, tone);

    let mut right = c.right();
    let close = Rect::from_min_size(Pos2::new(right - 44.0, top), Vec2::splat(44.0));
    if glass::button_at(ui, "close", close, 22.0, false).clicked() {
        ui.ctx().send_viewport_cmd(egui::ViewportCommand::Close);
    }
    let k = 8.0;
    let st = egui::Stroke::new(2.2, white(1.0));
    ui.painter().line_segment([close.center() + Vec2::new(-k, -k), close.center() + Vec2::new(k, k)], st);
    ui.painter().line_segment([close.center() + Vec2::new(-k, k), close.center() + Vec2::new(k, -k)], st);
    right -= 44.0 + 8.0;

    let settings = Rect::from_min_size(Pos2::new(right - 44.0, top), Vec2::splat(44.0));
    if glass::icon_button_at(ui, "settings", settings, icons::SETTINGS_COG).clicked() {
        app.page = Page::Settings;
    }
    right -= 44.0 + 8.0;

    let dev = Rect::from_min_size(Pos2::new(right - 44.0, top), Vec2::splat(44.0));
    if glass::icon_button_at(ui, "dev", dev, icons::DEV_TOOLS).clicked() {
        app.page = Page::Dev;
    }
}

/// The Home page under the header. Returns the bottom of the last row.
pub fn show(app: &mut App, ui: &mut Ui, body: Rect) -> f32 {
    disconnect_sheet(app, ui);
    spatial_sheet(app, ui);
    codec_sheet(app, ui);
    let c = column(body);
    let mut y = c.top();
    let battery = Rect::from_min_size(Pos2::new(c.left(), y), Vec2::new(c.width(), BATTERY));
    battery_at(app, ui, battery);
    y += BATTERY + GAP;

    let noise = Rect::from_min_size(Pos2::new(c.left(), y), Vec2::new(c.width(), NOISE));
    noise_at(app, ui, noise, true);
    y += NOISE + GAP;

    rows(app, ui, &c, y)
}

/// The disconnect question of the connection pill (`ConfirmDialog`): Cancel keeps the link.
fn disconnect_sheet(app: &mut App, ui: &mut Ui) {
    if !app.disconnect_ask { return; }
    let tr = app.tr.clone();
    let name = if app.snap.name.is_empty() { "QuickBuds".to_string() } else { app.snap.name.clone() };
    let title = t(&tr, "disconnect_title").replace("%1$s", &name);
    let mut decision = None;
    let open = crate::settings::sheets::sheet(ui.ctx(), "disconnect", &title, crate::settings::sheets::Title::Centre,
        Vec2::new(crate::settings::sheets::WIDTH, 263.2), |ui, inner| {
            let msg = Rect::from_min_max(inner.min + Vec2::new(14.0, 4.0), Pos2::new(inner.right() - 14.0, inner.bottom() - 66.0));
            crate::phone::paragraph(ui, msg, t(&tr, "disconnect_body"), 14.0, white(0.80), true);
            decision = crate::settings::sheets::actions(ui, inner, t(&tr, "dialog_cancel"), t(&tr, "conn_action_disconnect"));
        });
    match decision {
        Some(true) => { app.send(Cmd::Disconnect); app.disconnect_ask = false; }
        Some(false) => app.disconnect_ask = false,
        None if !open => app.disconnect_ask = false,
        None => {}
    }
}

/// The 3D audio type picker (`spatialSheet`): Off, Fixed, Head tracking. A type that turns Hi-Res off
/// asks first, as the switch does; the buds restart the link.
fn spatial_sheet(app: &mut App, ui: &mut Ui) {
    if !app.spatial_pick { return; }
    let s = app.snap.clone();
    let list = [0u8, 1, 2];
    let options: Vec<String> = list.iter().map(|t| spatial_label(&app.tr, *t)).collect();
    let cur = list.iter().position(|t| Some(*t) == s.spatial_type).unwrap_or(0);
    match glass::choice_dialog(ui.ctx(), "spatial-pick", t(&app.tr, "row_spatial_title"), &options, cur) {
        glass::Choice::Pick(i) => {
            let cmds = home_logic::spatial_cmds(&s, list[i]);
            let drops_hires = list[i] != 0 && s.features.contains(&(FEATURE_HIRES, 1)) && home_logic::exclusive(&s);
            if drops_hires {
                app.confirm = Some(crate::Confirm { cmds, title: "codec_dialog_title", msg: "codec_msg_spatial_drops_hires", accept: "codec_dialog_accept" });
            } else {
                for c in cmds { app.send(c); }
            }
            app.spatial_pick = false;
        }
        glass::Choice::Close => app.spatial_pick = false,
        glass::Choice::Open => {}
    }
}

/// The codec picker (`codecSheet`): the offered codecs, the current one marked. A different codec
/// restarts the buds, so the write asks first.
fn codec_sheet(app: &mut App, ui: &mut Ui) {
    if !app.codec_pick { return; }
    let s = app.snap.clone();
    let list = codec_list(&s);
    // The buds answer the list after the handshake: until then the picker has nothing to show.
    if list.is_empty() { app.codec_pick = false; return; }
    let options: Vec<String> = list.iter().map(|c| types::codec_name(*c)).collect();
    let cur = list.iter().position(|c| Some(*c) == s.codec).unwrap_or(usize::MAX);
    match glass::choice_dialog(ui.ctx(), "codec-pick", t(&app.tr, "row_hires_title"), &options, cur) {
        glass::Choice::Pick(i) => {
            if Some(list[i]) != s.codec {
                let hires = s.features.contains(&(FEATURE_HIRES, 1));
                app.confirm = Some(crate::Confirm { cmds: vec![Cmd::Codec(list[i], hires)], title: "codec_dialog_title", msg: "codec_msg_reconnect", accept: "codec_dialog_accept" });
            }
            app.codec_pick = false;
        }
        glass::Choice::Close => app.codec_pick = false,
        glass::Choice::Open => {}
    }
}

/// Left, case, right, at the measured heights: rings 90 dp, percentage at 131 dp, wear at 155.4 dp
/// and the device name 10 dp under the 152 dp status block.
pub(crate) fn battery_at(app: &App, ui: &Ui, r: Rect) {
    glass::flat_at(ui, r, RADIUS_CARD, 1.0);
    let s = &app.snap;
    let on = s.status == Status::On;
    let blind = !crate::overview::wear_sensor_seen(s, app.demo);
    let tr = &app.tr;
    let wear = |i: usize| match s.wear[i] {
        3 | 7 => t(tr, "status_in_ear"),
        4 => t(tr, "status_in_case"),
        1 | 5 if !blind => t(tr, "status_out"),
        1 | 5 => t(tr, "status_in_ear"),
        _ => "",
    };
    let slot = r.top() + SLOT_TOP;
    for (n, (i, glyph)) in [(0usize, icons::BUD_LEFT), (2, icons::CASE), (1, icons::BUD_RIGHT)].into_iter().enumerate() {
        let cx = r.left() + r.width() * (1.0 / 6.0 + n as f32 / 3.0);
        let b = s.battery[i];
        let frac = b.map(|x| x.0 as f32 / 100.0);
        let shown = glass::ring_at(ui, ("ring", n), Pos2::new(cx, slot + RING_TOP + RING_R), RING_R, if on { frac } else { None }, Some(glyph), !on);
        let pct = match (b, on) {
            (Some(_), true) => format!("{}%", (shown * 100.0).round() as i32),
            _ => "—".into(),
        };
        glass::text(ui, Pos2::new(cx, r.top() + PCT_Y), Align2::CENTER_CENTER, &pct, 21.0, if on { 1.0 } else { 0.5 });
        let sub = if i == 2 { t(tr, "status_case") } else { wear(i) };
        let lit = matches!(s.wear[i], 3 | 7) || (s.wear[i] == 5 && blind);
        glass::text(ui, Pos2::new(cx, r.top() + WEAR_Y), Align2::CENTER_CENTER, sub, 13.0, if lit { 1.0 } else { SECONDARY });
    }
    let name = if s.name.is_empty() { "QuickBuds" } else { s.name.as_str() };
    glass::text(ui, Pos2::new(r.center().x, r.top() + NAME_Y), Align2::CENTER_CENTER, name, 15.0, SECONDARY);
}

/// The noise control: one plate, four segments, and a lit pill that waits for the buds' report.
pub(crate) fn noise_at(app: &mut App, ui: &mut Ui, r: Rect, enabled: bool) {
    glass::flat_at(ui, r, RADIUS_CARD, 1.0);
    let on = app.snap.status == Status::On && enabled;
    let anc = app.snap.anc.clone().unwrap_or_default();
    let is_level = types::LEVELS.contains(&anc.as_str());
    let levels = app.modes.levels();
    let modes = app.modes.clone();

    // (label, icon, selected, command)
    let mut items: Vec<(String, &'static str, bool, String)> = vec![];
    if app.levels_open && on {
        for id in levels.iter() {
            let (icon, li) = level_info(id);
            items.push((t(&app.tr, LEVEL_NAMES[li]).into(), icon, anc == *id, id.to_string()));
        }
    } else {
        if modes.supports(types::OFF) {
            items.push((t(&app.tr, "anc_seg_off").into(), icons::MODE_OFF, anc == types::OFF, types::OFF.into()));
        }
        if !levels.is_empty() {
            let (icon, li) = level_info(if is_level { &anc } else { "" });
            let mut label = t(&app.tr, "anc_seg_anc").to_string();
            if is_level {
                if let Some(c) = t(&app.tr, LEVEL_NAMES[li]).chars().next() {
                    label = format!("{label} {}", c.to_uppercase());
                }
            }
            items.push((label, icon, is_level, "ANC".into()));
        }
        if modes.supports(types::ADAPTIVE) {
            items.push((t(&app.tr, "anc_seg_adapt").into(), icons::MODE_ADAPTIVE, anc == types::ADAPTIVE, types::ADAPTIVE.into()));
        }
        if modes.supports(types::TRANSPARENCY) {
            items.push((t(&app.tr, "anc_seg_trans").into(), icons::MODE_TRANSPARENCY, anc == types::TRANSPARENCY, types::TRANSPARENCY.into()));
        }
    }

    let n = items.len().max(1);
    let inner = r.shrink(4.0);
    let seg_w = inner.width() / n as f32;
    let sel = if on { items.iter().position(|i| i.2) } else { None };
    let target = sel.map_or(0.0, |i| inner.left() + seg_w * i as f32);
    let x = ui.ctx().animate_value_with_time(egui::Id::NULL.with("home-anc-x"), target, 0.24);
    let vis = ui.ctx().animate_bool_with_time(egui::Id::NULL.with("home-anc-v"), sel.is_some(), 0.18);
    if vis > 0.0 {
        // The lit pill. Glass: 20 dp radius, the accent fill (DESIGN §4.7); the capture is #A8ABB9.
        let pill = Rect::from_min_size(Pos2::new(x, inner.top()), Vec2::new(seg_w, inner.height()));
        ui.painter().rect_filled(pill, 20.0, white(0.66 * vis));
    }

    let mut clicked = None;
    for (i, (label, icon, selected, cmd)) in items.iter().enumerate() {
        let seg = Rect::from_min_size(Pos2::new(inner.left() + seg_w * i as f32, inner.top()), Vec2::new(seg_w, inner.height()));
        let resp = ui.interact(seg, egui::Id::NULL.with(("home-anc", i)), egui::Sense::click());
        if resp.hovered() && on {
            ui.painter().rect_filled(seg.shrink(2.0), 18.0, white(0.07));
            ui.ctx().set_cursor_icon(egui::CursorIcon::PointingHand);
        }
        let lit = if *selected { vis } else { 0.0 };
        let ink = mix(white(SECONDARY), glass::on_accent(), lit);
        glass::icon_at(ui, icon, Rect::from_center_size(Pos2::new(seg.center().x, r.top() + 24.0), Vec2::splat(22.0)), ink);
        let label_col = if *selected && on { glass::on_accent() } else { white(SECONDARY * if on { 1.0 } else { 0.5 }) };
        ui.painter().text(Pos2::new(seg.center().x, r.top() + 46.0), Align2::CENTER_CENTER, label, FontId::proportional(11.5), label_col);
        if resp.clicked() && on {
            clicked = Some((cmd.clone(), *selected));
        }
    }
    if let Some((cmd, selected)) = clicked {
        if app.levels_open {
            app.send(Cmd::Anc(if selected { types::OFF.into() } else { cmd }));
            app.levels_open = false;
        } else if cmd == "ANC" {
            if levels.len() > 1 {
                app.levels_open = true;
            } else if let Some(l) = levels.first() {
                app.send(Cmd::Anc(l.to_string()));
            }
        } else {
            app.send(Cmd::Anc(cmd));
        }
    }
}

fn level_info(id: &str) -> (&'static str, usize) {
    match id {
        "ANC-Light" => (icons::MODE_ANC_LOW, 0),
        "ANC-Deep" => (icons::MODE_ANC_HIGH, 2),
        "ANC-Smart" => (icons::MODE_ANC_SMART, 3),
        _ => (icons::MODE_ANC_MEDIUM, 1),
    }
}

const LEVEL_NAMES: [&str; 4] = ["anc_mode_low", "anc_mode_medium", "anc_mode_high", "anc_mode_smart"];

pub(crate) struct Row {
    pub(crate) id: &'static str,
    pub(crate) icon: &'static str,
    pub(crate) title: String,
    pub(crate) sub: String,
    pub(crate) trail: Trailing,
    pub(crate) enabled: bool,
    pub(crate) page: Option<Page>,
    pub(crate) confirm: Option<&'static str>,
}

/// The phone's row rule (DESIGN §7.1): wind noise, low latency, high-quality audio and 3D audio when
/// the buds list them, then hearing, equalizer, dual connection and earbud settings.
pub(crate) fn row_list(app: &App) -> Vec<Row> {
    let s = &app.snap;
    let on = s.status == Status::On;
    let tr = &app.tr;
    let listed = |id: u8| s.features.iter().find(|f| f.0 == id).map(|f| f.1);
    let flag = |k: &str| s.manual && s.model.is_some_and(|m| m[k] == 1);
    let busy = |id: u8| s.busy.contains(&id);
    let sub = |key: &str, id: u8| if busy(id) { t(tr, "feature_applying").to_string() } else { t(tr, key).to_string() };

    let mut rows: Vec<Row> = vec![];
    if let Some(v) = listed(FEATURE_WIND_NOISE).or(flag("windMode").then_some(0)) {
        rows.push(Row { id: "wind", icon: icons::ANC, title: t(tr, "row_wind_noise_title").into(), sub: sub("row_wind_noise_sub", FEATURE_WIND_NOISE),
            trail: Trailing::Switch(v == 1), enabled: on && !busy(FEATURE_WIND_NOISE), page: None, confirm: None });
    }
    if types::has_feature(&s.features, s.caps.game_mode_id()) {
        let id = s.caps.game_mode_id();
        rows.push(Row { id: "game", icon: icons::LOW_LATENCY, title: t(tr, "row_game_title").into(), sub: sub("row_game_sub", id),
            trail: Trailing::Switch(s.low_latency.unwrap_or(false)), enabled: on && !busy(id), page: None, confirm: None });
    }
    let hires_on = listed(FEATURE_HIRES) == Some(1);
    let picker = home_logic::codec_picker(s);
    if listed(FEATURE_HIRES).is_some() || picker {
        let msg = if !picker && home_logic::spatial_on(s) && home_logic::exclusive(s) { "codec_msg_hires_drops_spatial" } else { "codec_msg_reconnect" };
        rows.push(Row { id: "hires", icon: icons::HIRES, title: t(tr, "row_hires_title").into(),
            sub: hires_sub(s, tr, busy(FEATURE_HIRES)),
            // HeyMelody's codec picker: a list of the codecs, not a switch (PROTOCOL.md section 18).
            trail: if picker { Trailing::Chevron } else { Trailing::Switch(hires_on) },
            enabled: on && !busy(FEATURE_HIRES), page: None, confirm: if picker { None } else { Some(msg) } });
    }
    if listed(FEATURE_SPATIAL).is_some() || home_logic::spatial_by_type(s) {
        let msg = if hires_on && home_logic::exclusive(s) { Some("codec_msg_spatial_drops_hires") } else { None };
        rows.push(Row { id: "spatial", icon: icons::SPATIAL, title: t(tr, "row_spatial_title").into(),
            sub: spatial_sub(s, tr, busy(FEATURE_SPATIAL)),
            trail: Trailing::Switch(home_logic::spatial_on(s)), enabled: on && !busy(FEATURE_SPATIAL), page: None, confirm: msg });
    }
    if app.has.hearing {
        let v = listed(FEATURE_HEARING) == Some(1);
        rows.push(Row { id: "hearing", icon: icons::HEARING, title: t(tr, "row_golden_title").into(), sub: sub("row_golden_sub", FEATURE_HEARING),
            trail: Trailing::Switch(v), enabled: on && !busy(FEATURE_HEARING), page: Some(Page::Hearing), confirm: None });
    }
    let nav = |id, icon, title: &str, sub: String, page| Row { id, icon, title: title.into(), sub, trail: Trailing::Chevron, enabled: true, page: Some(page), confirm: None };
    if app.has.eq {
        rows.push(nav("eq", icons::EQUALIZER, t(tr, "eq_title"), t(tr, "row_eq_sub").into(), Page::Eq));
    }
    if app.has.dual {
        rows.push(nav("dual", icons::DEVICES, t(tr, "dual_title"), t(tr, "row_dual_sub").into(), Page::Dual));
    }
    rows.push(nav("earbuds", icons::SETTINGS_COG, t(tr, "earbuds_title"), t(tr, "row_earbuds_sub").into(), Page::Earbuds));
    rows
}

/// The Hi-Res row's subtitle (the phone's `paintHires`): the codec on a picker row, the hint when the
/// phone plays a lower codec, else the feature's own text.
fn hires_sub(s: &Snapshot, tr: &[String], busy: bool) -> String {
    if busy { return t(tr, "feature_applying").into(); }
    let on = s.features.contains(&(FEATURE_HIRES, 1));
    let key = if on { "row_hires_sub" } else { "row_hires_sub_off" };
    if home_logic::codec_picker(s) {
        return s.codec.map_or_else(|| t(tr, key).into(), types::codec_name);
    }
    // Quality on, but the phone plays another codec than LHDC: name the model's best codec (report #17).
    match (on, s.codec, hires_best_codec(s)) {
        (true, Some(played), Some(best)) if played != 7 && played != 8 => {
            t(tr, "row_hires_sub_codec").replace("%1$s", &types::codec_name(played)).replace("%2$s", best)
        }
        _ => t(tr, key).into(),
    }
}

/// The best codec to name in the hint (`ModelCatalog.hiresBestCodec`): the model's own list, else the
/// buds' offered list.
fn hires_best_codec(s: &Snapshot) -> Option<&'static str> {
    if s.model.is_some_and(|m| m.get("codecs").is_some()) { return types::best_hires_codec(s.model); }
    if s.codecs.contains(&8) { Some("LHDC 5.0") } else if s.codecs.contains(&3) { Some("LDAC") } else if s.codecs.contains(&7) { Some("LHDC") } else { None }
}

/// The 3D audio type picker (`spatialSheet`): head tracking on the model, on buds that take a type through
/// `0x0422`. Every other model keeps the plain switch.
fn spatial_picker(s: &Snapshot) -> bool {
    home_logic::spatial_by_type(s) && home_logic::head_tracking(s)
}

/// The type names of the picker (`spatialLabel`): Off, Fixed, Head tracking.
fn spatial_label(tr: &[String], ty: u8) -> String {
    t(tr, match ty { 1 => "spatial_fixed", 2 => "spatial_head_tracking", _ => "anc_seg_off" }).into()
}

/// The 3D audio row's subtitle: the type the buds play on a picker model, else the feature's own text.
fn spatial_sub(s: &Snapshot, tr: &[String], busy: bool) -> String {
    if busy { return t(tr, "feature_applying").into(); }
    if spatial_picker(s) { return spatial_label(tr, s.spatial_type.unwrap_or(0)); }
    t(tr, "row_spatial_sub").into()
}

/// The codecs the sheet offers, best first: LHDC V5, LHDC, LDAC, aptX Adaptive, aptX HD, aptX, AAC, SBC.
fn codec_list(s: &Snapshot) -> Vec<u8> {
    [8, 7, 3, 6, 5, 4, 2, 1].into_iter().filter(|c| s.codecs.contains(c)).collect()
}

/// The saved order (`home_rows`) and hidden set (`home_hidden`) applied to the supported rows.
pub(crate) fn home_rows(app: &App) -> Vec<Row> {
    let st = crate::load_settings();
    let saved: Vec<&str> = st["home_rows"].as_str().unwrap_or("").split(',').filter(|s| !s.is_empty()).collect();
    let mut rows = row_list(app);
    rows.sort_by_key(|r| saved.iter().position(|k| *k == r.id).unwrap_or(usize::MAX));
    let hidden: Vec<String> = st["home_hidden"].as_array().map(|a| a.iter().filter_map(|v| v.as_str().map(String::from)).collect()).unwrap_or_default();
    rows.retain(|r| !hidden.iter().any(|k| k == r.id));
    rows
}

fn rows(app: &mut App, ui: &mut Ui, c: &Rect, mut y: f32) -> f32 {
    for r in home_rows(app) {
        let text_w = c.width() - TEXT_X - trail_width(&r.trail);
        let sub_col = white(SECONDARY * if r.enabled { 1.0 } else { 0.5 });
        let galley = ui.painter().layout(r.sub.clone(), FontId::proportional(13.0), sub_col, text_w);
        let lines = galley.rows.len().max(1) as f32;
        let h = ROW + ROW_LINE * (lines - 1.0);
        let rect = Rect::from_min_size(Pos2::new(c.left(), y), Vec2::new(c.width(), h));
        let (clicked, toggled) = row_at(ui, &r, rect, galley, sub_col);
        if toggled {
            let now = matches!(r.trail, Trailing::Switch(true));
            let cmds = match r.id {
                "wind" => vec![Cmd::Features(vec![(FEATURE_WIND_NOISE, !now)])],
                "game" => vec![Cmd::LowLatency(!now)],
                "hires" => home_logic::feature_cmds(&app.snap, FEATURE_HIRES, !now),
                "spatial" => home_logic::feature_cmds(&app.snap, FEATURE_SPATIAL, !now),
                "hearing" => home_logic::feature_cmds(&app.snap, FEATURE_HEARING, !now),
                _ => vec![],
            };
            // Hi-Res asks both ways, 3D audio only when it turns Hi-Res off (the phone's rules).
            let ask = r.confirm.filter(|_| r.id == "hires" || !now);
            match ask {
                Some(msg) => app.confirm = Some(crate::Confirm { cmds, title: "codec_dialog_title", msg, accept: "codec_dialog_accept" }),
                None => for c in cmds { app.send(c); },
            }
        }
        if clicked {
            if let Some(p) = r.page { app.page = p; }
            // A tap on the Hi-Res row of a model with a codec list opens the picker, and one on a 3D audio row
            // of a head tracking model opens its types (the phone's `spatialSheet`).
            else if r.id == "hires" && home_logic::codec_picker(&app.snap) { app.codec_pick = true; }
            else if r.id == "spatial" && spatial_picker(&app.snap) { app.spatial_pick = true; }
        }
        y += h + ROW_GAP;
    }
    y - ROW_GAP
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::translations;

    fn model(name: &str) -> Option<&'static serde_json::Value> {
        types::models().iter().find(|m| m["name"].as_str() == Some(name))
    }

    /// The picker row shows the codec the buds play, as the phone does (`paintHires`).
    #[test]
    fn picker_row_shows_the_codec() {
        let tr = translations("");
        let s = Snapshot { model: model("OPPO Enco X2"), codec: Some(3), features: vec![(FEATURE_HIRES, 1)], ..Default::default() };
        assert!(home_logic::codec_picker(&s));
        assert_eq!(hires_sub(&s, &tr, false), "LDAC");
        let s = Snapshot { codec: Some(2), ..s };
        assert_eq!(hires_sub(&s, &tr, false), "AAC");
    }

    /// Before the buds report a codec, the picker row keeps the feature's own text.
    #[test]
    fn picker_row_without_a_codec_keeps_the_feature_text() {
        let tr = translations("");
        let s = Snapshot { model: model("OPPO Enco X2"), features: vec![(FEATURE_HIRES, 1)], ..Default::default() };
        assert_eq!(hires_sub(&s, &tr, false), t(&tr, "row_hires_sub"));
    }

    /// Hi-Res on a lower codec names that codec and the best one, from the model's list or the offer.
    #[test]
    fn lower_codec_shows_the_hint() {
        let tr = translations("");
        let s = Snapshot { model: model("OnePlus Buds 4"), features: vec![(FEATURE_HIRES, 1)], codec: Some(2), codecs: vec![2, 3], ..Default::default() };
        let hint = hires_sub(&s, &tr, false);
        assert!(hint.contains("AAC") && hint.contains("LHDC 5.0"), "{hint}");
        // Without a model list, the buds' offered codecs answer.
        let s = Snapshot { model: None, features: vec![(FEATURE_HIRES, 1)], codec: Some(2), codecs: vec![2, 3], ..Default::default() };
        let hint = hires_sub(&s, &tr, false);
        assert!(hint.contains("AAC") && hint.contains("LDAC"), "{hint}");
    }

    /// LHDC and LDAC play Hi-Res: the row keeps its own text, and so does a Hi-Res row that is off.
    #[test]
    fn hires_codec_and_hires_off_keep_the_feature_text() {
        let tr = translations("");
        let on = Snapshot { model: model("OnePlus Buds 4"), features: vec![(FEATURE_HIRES, 1)], codec: Some(8), codecs: vec![8, 2], ..Default::default() };
        assert_eq!(hires_sub(&on, &tr, false), t(&tr, "row_hires_sub"));
        let off = Snapshot { features: vec![(FEATURE_HIRES, 0)], codec: Some(2), ..on };
        assert_eq!(hires_sub(&off, &tr, false), t(&tr, "row_hires_sub_off"));
    }

    /// A write in progress wins over every other text, and the row takes no tap.
    #[test]
    fn a_busy_row_says_applying() {
        let tr = translations("");
        let s = Snapshot { model: model("OPPO Enco X2"), codec: Some(3), ..Default::default() };
        assert_eq!(hires_sub(&s, &tr, true), t(&tr, "feature_applying"));
    }

    /// HeyMelody's order: LHDC V5, LHDC, LDAC, aptX Adaptive, aptX HD, aptX, AAC, SBC, offered only.
    #[test]
    fn the_sheet_lists_the_offered_codecs_best_first() {
        let s = Snapshot { codecs: vec![2, 3, 8, 6], ..Default::default() };
        assert_eq!(codec_list(&s), [8, 3, 6, 2]);
    }

    /// The type picker is for a model with head tracking on buds that take a type; every other model keeps
    /// the plain switch (`RowRules.spatialByType` and `spatialModes` on the phone).
    #[test]
    fn the_picker_follows_the_model_and_the_buds() {
        assert!(spatial_picker(&Snapshot { model: model("OnePlus Buds Pro 2"), ..Default::default() }));
        // realme: 3D audio is feature `1B`, written alone, so a plain switch.
        assert!(!spatial_picker(&Snapshot { model: model("realme Buds Air 5 Pro"), ..Default::default() }));
        // Buds without the `0x012A` read keep the switch, even on a model that offers head tracking.
        let caps = types::Caps(Some(vec![types::CMD_SET_FEATURE]));
        assert!(!spatial_picker(&Snapshot { model: model("OnePlus Buds Pro 2"), caps, ..Default::default() }));
    }

    /// Each choice of the picker writes its own type: 0 off, 1 fixed, 2 head tracking.
    #[test]
    fn each_choice_writes_its_type() {
        let s = Snapshot { model: model("OnePlus Buds Pro 2"), spatial_type: Some(1), ..Default::default() };
        for t in [0u8, 1, 2] {
            let cmds = home_logic::spatial_cmds(&s, t);
            assert!(matches!(cmds.as_slice(), [Cmd::SpatialType(v)] if *v == t), "type {t}");
        }
    }

    /// A type that turns 3D audio on writes Hi-Res off after it, as HeyMelody sends them.
    #[test]
    fn a_type_that_turns_it_on_drops_hires() {
        let s = Snapshot { model: model("OnePlus Buds Pro 2"), features: vec![(FEATURE_HIRES, 1)], ..Default::default() };
        let cmds = home_logic::spatial_cmds(&s, 2);
        assert!(matches!(cmds.as_slice(), [Cmd::SpatialType(2), Cmd::Features(v)] if v.as_slice() == [(FEATURE_HIRES, false)]));
    }

    /// The row names the type the buds play on a head tracking model, and its own text otherwise.
    #[test]
    fn the_row_names_the_type() {
        let tr = translations("");
        let buds = Snapshot { model: model("OnePlus Buds Pro 2"), spatial_type: Some(2), ..Default::default() };
        assert_eq!(spatial_sub(&buds, &tr, false), t(&tr, "spatial_head_tracking"));
        let realme = Snapshot { model: model("realme Buds Air 5 Pro"), ..Default::default() };
        assert_eq!(spatial_sub(&realme, &tr, false), t(&tr, "row_spatial_sub"));
        assert_eq!(spatial_sub(&buds, &tr, true), t(&tr, "feature_applying"));
    }
}

/// A 59.8 dp row: a 24 dp icon, a 16 sp title, a 13 sp subtitle that wraps, and a switch or a chevron.
fn row_at(ui: &mut Ui, r: &Row, rect: Rect, galley: std::sync::Arc<egui::Galley>, sub_col: Color32) -> (bool, bool) {
    let resp = ui.interact(rect, ui.scope_id().with(r.id), egui::Sense::click());
    let hover = ui.ctx().animate_bool_with_time(resp.id.with("h"), resp.hovered() && r.enabled, 0.12);
    glass::flat_at(ui, rect, RADIUS_ROW, 1.0 + 0.4 * hover);
    let a = if r.enabled { 1.0 } else { 0.5 };
    glass::icon_at(ui, r.icon, Rect::from_center_size(Pos2::new(rect.left() + PAD + ICON / 2.0, rect.center().y), Vec2::splat(ICON)), white(SECONDARY * a));
    let text_w = rect.width() - TEXT_X - trail_width(&r.trail);
    glass::text(ui, Pos2::new(rect.left() + TEXT_X, rect.top() + TITLE_CY), Align2::LEFT_CENTER, &fit(ui, &r.title, 16.0, text_w), 16.0, a);
    ui.painter().galley(Pos2::new(rect.left() + TEXT_X, rect.top() + SUB_TOP), galley, sub_col);
    let mut toggled = false;
    match r.trail {
        Trailing::Switch(on) => {
            toggled = glass::switch_at(ui, (r.id, "sw"), Pos2::new(rect.right() - PAD - 52.0, rect.center().y - 15.0), on, r.enabled);
        }
        Trailing::Chevron => {
            let c = Pos2::new(rect.right() - PAD - 12.0, rect.center().y);
            glass::icon_at(ui, icons::CHEVRON_RIGHT, Rect::from_center_size(c, Vec2::splat(24.0)), white(SECONDARY * a));
        }
        _ => {}
    }
    if r.enabled && resp.hovered() {
        ui.ctx().set_cursor_icon(egui::CursorIcon::PointingHand);
    }
    (r.enabled && resp.clicked() && !toggled, toggled)
}
