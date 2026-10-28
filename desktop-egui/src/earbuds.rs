//! Earbud settings (`EarbudSettingsActivity`, DESIGN §7.12): the gestures, wear, find and fit card, the alert volume,
//! the tap sensitivity, the feature switches and the firmware. One 379.4 dp column, in the phone's order.

use crate::glass::{self, Choice};
use crate::home_logic::{self, ROWS};
use crate::phone;
use crate::session::{Cmd, Snapshot, Status};
use crate::settings::sheets::{self, Title};
use crate::settings::ui;
use crate::settings::ui::Trail;
use crate::types::*;
use crate::{icons, load_settings, save_setting, t, App, Confirm};
use eframe::egui::{self, Align2, Pos2, Rect, Ui, Vec2};
use std::time::{Duration, Instant};

/// Feature `0x04`: the buds' own auto play/pause, shown in the Wear sheet, not in the Features card.
const FEATURE_AUTO_PLAY_PAUSE: u8 = 0x04;

#[derive(Default)]
pub struct State {
    /// The picker dialog open: (kind 1 game sound, 2 head gesture).
    picking: Option<u8>,
    alert: Option<f32>,
    tap: Option<f32>,
    wear_open: bool,
    // Find my earbuds: the warning before a loud tone, and the tone playing.
    find_open: bool,
    find_warn: bool,
    find_playing: bool,
    // Fit test: 0 ready, 1 insert the buds, 2 running, 3 failed, 4 perfect, 5 adjust.
    fit_open: bool,
    fit_state: u8,
    fit_started: bool,
    fit_shown: bool,
    fit_deadline: Option<Instant>,
    fit_seq: u32,
    firmware_open: bool,
    // Personalized ANC: 0 asking, 1 stored result offered, 2 test intro, 3 testing, 4 failed, 5 wear both buds, 6 done.
    pnc_open: bool,
    pnc_state: u8,
    pnc_reason: u8,
    pnc_deadline: Option<Instant>,
    pnc_seq: u32,
}

/// HeyMelody's names for the game sound types; a type without one is not offered.
fn game_label(tr: &[String], v: u8) -> Option<String> {
    Some(t(tr, match v { 0 => "anc_seg_off", 1 => "game_sound_type_peace", 3 => "game_sound_type_shooter", _ => return None }).into())
}

fn head_label(tr: &[String], v: u8) -> Option<String> {
    Some(t(tr, match v { 0 => "head_motion_nod", 1 => "head_motion_shake", _ => return None }).into())
}

/// The Wear row and sheet show when the buds list auto play/pause. The model's `confirmed` list is the fallback
/// before a feature read, so the row is there on the first frame as it is on the phone.
fn wear_offered(s: &Snapshot) -> bool {
    s.features.iter().any(|f| f.0 == FEATURE_AUTO_PLAY_PAUSE)
        || s.model.is_some_and(|m| m["confirmed"].as_array().is_some_and(|c| c.iter().any(|x| x.as_str() == Some("wear"))))
}

/// The Game equalizer row and its switch: the buds list feature `0x21` and accept `0x0420` (`EarbudSettingsActivity`).
/// The Usage access row that goes with the switch on the phone has no meaning on the desktop.
fn game_eq_row(s: &Snapshot) -> Option<bool> {
    let v = s.features.iter().find(|f| f.0 == FEATURE_GAME_EQ)?.1;
    s.caps.supports(CMD_GAME_EQ_STATE).then_some(v == 1)
}

pub fn show(app: &mut App, ui: &mut Ui, body: Rect) -> f32 {
    let s = app.snap.clone();
    let tr = app.tr.clone();
    let w = phone::content(body);
    let x0 = body.left();
    let on = s.status == Status::On;
    let accent = crate::palette::active(crate::palette::is_light_system(ui.ctx()), app.style == glass::style::Style::Material).accent;
    let flagged = |flag: Option<&str>| s.manual && flag.is_some_and(|f| s.model.is_some_and(|m| m[f] == 1));
    let mut y = body.top();

    // The card of links: gestures, wear, find, fit. Each is its own 16 dp row card, 8 dp apart.
    let gestures = crate::gestures::available(&s);
    let wear = wear_offered(&s);
    let find = s.caps.supports(CMD_FIND_BUDS);
    let fit = s.caps.supports(CMD_FIT_TEST);
    let mut any = false;
    if gestures {
        let (h, clicked, _) = ui::row_card(ui, "gestures", x0, y, w, Some(icons::GESTURE), t(&tr, "row_gesture_title"), t(&tr, "row_gesture_sub"), Trail::Chevron, on);
        if clicked { app.page = crate::Page::Gestures; }
        y += h + 8.0;
        any = true;
    }
    if wear {
        let (h, clicked, _) = ui::row_card(ui, "wear", x0, y, w, Some(icons::EARBUD), t(&tr, "row_wear_title"), t(&tr, "row_wear_sub"), Trail::Chevron, on);
        if clicked { app.earbuds.wear_open = true; }
        y += h + 8.0;
        any = true;
    }
    if find {
        let (h, clicked, _) = ui::row_card(ui, "find", x0, y, w, Some(icons::FIND_BUDS), t(&tr, "row_find_title"), t(&tr, "row_find_sub"), Trail::Chevron, on);
        if clicked { app.earbuds.find_open = true; app.earbuds.find_warn = false; }
        y += h + 8.0;
        any = true;
    }
    if fit {
        let (h, clicked, _) = ui::row_card(ui, "fit", x0, y, w, Some(icons::EARBUD), t(&tr, "fit_title"), t(&tr, "fit_sub"), Trail::Chevron, on);
        if clicked { app.earbuds.fit_open = true; app.earbuds.fit_state = 0; app.earbuds.fit_shown = false; app.earbuds.fit_seq = s.fit.2; }
        y += h + 8.0;
        any = true;
    }
    if any { y -= 8.0; }

    // Sounds: the alert volume. The value is not shown; the speaker is muted at level 1.
    let has_alert = s.caps.supports(CMD_SET_ALERT_VOLUME) && s.alert_volume.is_some();
    if has_alert {
        ui::section(ui, x0, y, t(&tr, "earbuds_section_sounds"), accent);
        y += phone::SECTION;
        let card = Rect::from_min_size(Pos2::new(x0, y), Vec2::new(w, 85.0));
        glass::flat_at(ui, card, phone::RADIUS, 1.0);
        glass::text(ui, Pos2::new(x0 + 18.0, y + 25.0), Align2::LEFT_CENTER, t(&tr, "row_alert_title"), 15.0, 1.0);
        let mut v = app.earbuds.alert.unwrap_or(s.alert_volume.unwrap_or(5) as f32);
        glass::icon_at(ui, if v <= 1.0 { icons::VOLUME_OFF } else { icons::VOLUME }, Rect::from_center_size(Pos2::new(x0 + 29.0, y + 57.0), Vec2::splat(22.0)), glass::white(1.0));
        let sr = Rect::from_min_size(Pos2::new(x0 + 40.0, y + 35.0), Vec2::new(w - 50.0, 44.0));
        let sl = glass::slider_at(ui, "alert", sr, &mut v, 1.0, 10.0, true);
        if sl.changed { app.earbuds.alert = Some(v); }
        if sl.released { app.send(Cmd::AlertVolume(v as u8)); app.earbuds.alert = None; }
        y += 85.0;
    }

    // Tap sensitivity: a section label, a hint, the slider and the warning below the buds' default.
    let has_tap = s.model.is_some_and(|m| m["tapLevel"] == 1) && (s.manual || s.caps.supports(CMD_SET_TAP_LEVEL) && s.tap_level.is_some());
    if has_tap {
        ui::section(ui, x0, y, t(&tr, "tap_level_title"), accent);
        y += phone::SECTION;
        let hint = t(&tr, "tap_level_hint");
        let hint_h = phone::wrap_height(ui, hint, 13.0, w - 36.0);
        let mut v = app.earbuds.tap.unwrap_or(s.tap_level.map_or(3, |v| v.0) as f32);
        let default = s.tap_level.map_or(0, |v| v.1) as f32;
        let warn = default > 0.0 && v < default;
        let warn_text = t(&tr, "tap_level_warning");
        let warn_h = if warn { phone::wrap_height(ui, warn_text, 13.0, w - 36.0) } else { 0.0 };
        let h = 14.0 + hint_h + 58.0 + warn_h + if warn { 6.0 } else { 4.0 };
        let card = Rect::from_min_size(Pos2::new(x0, y), Vec2::new(w, h));
        glass::flat_at(ui, card, phone::RADIUS, 1.0);
        phone::paragraph(ui, Rect::from_min_size(Pos2::new(x0 + 18.0, y + 14.0), Vec2::new(w - 36.0, hint_h)), hint, 13.0, glass::white(0.74), false);
        let sr = Rect::from_min_size(Pos2::new(x0 + 18.0, y + 14.0 + hint_h), Vec2::new(w - 60.0, 58.0));
        let sl = glass::slider_at(ui, "tap", sr, &mut v, 1.0, 5.0, true);
        ui.painter().text(Pos2::new(x0 + w - 18.0, sr.center().y), Align2::RIGHT_CENTER, format!("{}", v as i32), egui::FontId::proportional(14.0), accent);
        if sl.changed { app.earbuds.tap = Some(v); }
        if sl.released { app.send(Cmd::TapLevel(v as u8)); app.earbuds.tap = None; }
        if warn {
            phone::paragraph(ui, Rect::from_min_size(Pos2::new(x0 + 18.0, y + 14.0 + hint_h + 58.0), Vec2::new(w - 36.0, warn_h)), warn_text, 13.0, glass::white(0.74), false);
        }
        y += h;
    }

    // Feature switches, in the phone's order. Auto play/pause is in the Wear sheet, not here.
    struct Item { id: u8, icon: &'static str, title: &'static str, sub: &'static str, on: bool, confirm: Option<&'static str>, confirm_off: bool, kind: u8, choice: Option<String> }
    let mut items: Vec<Item> = vec![];
    for &(id, flag, icon, title, sub, confirm, confirm_off) in ROWS {
        if id == FEATURE_AUTO_PLAY_PAUSE { continue; }
        let v = match s.features.iter().find(|f| f.0 == id) { Some(f) => f.1, None if flagged(flag) => 0, None => continue };
        let (kind, choice) = match id {
            0x27 if s.manual || s.caps.supports(CMD_GAME_SOUND) => (1, s.game_sound.as_ref().and_then(|g| game_label(&tr, g.0))),
            0x3B if s.caps.supports(CMD_SET_HEAD_MOTION) => (2, s.head_motion.and_then(|v| head_label(&tr, v))),
            _ => (0, None),
        };
        items.push(Item { id, icon, title, sub, on: v == 1, confirm, confirm_off, kind, choice });
    }
    // Game equalizer, right after game sound in the phone's order (`EarbudSettingsActivity`), else before
    // the row that follows it there.
    if let Some(ge_on) = game_eq_row(&s) {
        let at = items.iter().position(|i| i.id == 0x27 || i.id == 0x1C)
            .map_or(items.len(), |p| p + (items[p].id == 0x27) as usize);
        items.insert(at, Item { id: FEATURE_GAME_EQ, icon: icons::LOW_LATENCY, title: "row_game_eq_title", sub: "row_game_eq_sub",
            on: ge_on, confirm: None, confirm_off: false, kind: 0, choice: None });
    }
    let pnc_listed = s.features.iter().find(|f| f.0 == FEATURE_PERSONAL_NOISE).map(|f| f.1);
    let pnc = s.model.map_or(true, |m| m["personalAnc"] == 1)
        && (pnc_listed.is_some() || flagged(Some("personalAnc")))
        && (s.caps.supports(CMD_PERSONAL_NOISE) || s.manual);
    if pnc || !items.is_empty() {
        ui::section(ui, x0, y, t(&tr, "earbuds_section_features"), accent);
        y += phone::SECTION;
        if pnc {
            let pon = pnc_listed == Some(1);
            let (h, _, toggled) = ui::row_card(ui, "pnc", x0, y, w, Some(icons::ANC), t(&tr, "pnc_title"), t(&tr, "pnc_sub"), Trail::Switch(pon), on);
            if toggled {
                if pon { app.send(Cmd::Features(vec![(FEATURE_PERSONAL_NOISE, false)])); } else { pnc_start(app); }
            }
            y += h + 8.0;
        }
        for i in items.iter() {
            let (h, _, toggled) = ui::row_card(ui, ("feat", i.id), x0, y, w, Some(i.icon), t(&tr, i.title), t(&tr, i.sub), Trail::Switch(i.on), on);
            if toggled {
                let cmds = home_logic::feature_cmds(&s, i.id, !i.on);
                match i.confirm.filter(|_| !i.on || i.confirm_off) {
                    Some(msg) => app.confirm = Some(Confirm { cmds, title: i.title, msg, accept: "codec_dialog_accept" }),
                    None => for c in cmds { app.send(c); },
                }
            }
            y += h + 8.0;
            if i.kind > 0 && i.on {
                let title = t(&tr, if i.kind == 1 { "game_sound_type_title" } else { "head_motion_type_title" });
                let value = i.choice.clone().unwrap_or_else(|| "—".into());
                let (h, clicked, _) = ui::row_card(ui, ("pick", i.id), x0, y, w, None, title, &value, Trail::Chevron, on);
                if clicked { app.earbuds.picking = Some(i.kind); }
                y += h + 8.0;
            }
        }
        y -= 8.0;
    }

    // About earbuds: the firmware. No updates here: a failed flash can brick the buds, so it points to HeyMelody.
    if s.caps.supports(CMD_QUERY_FIRMWARE) {
        ui::section(ui, x0, y, t(&tr, "earbuds_section_about"), accent);
        y += phone::SECTION;
        let (h, clicked, _) = ui::row_card(ui, "fw", x0, y, w, Some(icons::INFO), t(&tr, "row_firmware_title"), s.firmware.as_deref().unwrap_or("—"), Trail::None, true);
        if clicked { app.earbuds.firmware_open = true; }
        y += h;
    }

    // The picker dialog.
    if let Some(kind) = app.earbuds.picking {
        let (title, list): (&str, Vec<(u8, String)>) = if kind == 1 {
            let offered = s.game_sound.as_ref().map(|g| g.1.clone()).filter(|v| !v.is_empty()).unwrap_or(vec![3]);
            let mut game: Vec<u8> = vec![0];
            for v in offered { if !game.contains(&v) { game.push(v); } }
            ("game_sound_type_title", game.into_iter().filter_map(|v| Some((v, game_label(&tr, v)?))).collect())
        } else {
            ("head_motion_type_title", [0u8, 1].into_iter().filter_map(|v| Some((v, head_label(&tr, v)?))).collect())
        };
        let current = if kind == 1 { s.game_sound.as_ref().map(|g| g.0) } else { s.head_motion };
        let options: Vec<String> = list.iter().map(|(_, l)| l.clone()).collect();
        let cur = list.iter().position(|(v, _)| Some(*v) == current).unwrap_or(usize::MAX);
        match glass::choice_dialog(ui.ctx(), "earbuds-pick", t(&tr, title), &options, cur) {
            Choice::Pick(i) => {
                let v = list[i].0;
                app.send(if kind == 1 { Cmd::GameSoundType(v) } else { Cmd::HeadMotion(v) });
                app.earbuds.picking = None;
            }
            Choice::Close => app.earbuds.picking = None,
            Choice::Open => {}
        }
    }
    dialogs(app, ui);
    y + phone::GAP
}

/// (left, right) in an ear. Without an "in ear" seen, "out" may only mean the model has no sensor: counted as in.
fn worn(app: &App) -> (bool, bool) {
    let blind = !crate::overview::wear_sensor_seen(&app.snap, app.demo);
    let f = |w: u8| matches!(w, 3 | 7) || blind && matches!(w, 1 | 5);
    (f(app.snap.wear[0]), f(app.snap.wear[1]))
}

fn pnc_start(app: &mut App) {
    let (l, r) = worn(app);
    app.earbuds.pnc_open = true;
    app.earbuds.pnc_seq = app.snap.pnc.2;
    if !(l && r) { app.earbuds.pnc_state = 5; return; }
    app.earbuds.pnc_state = 0;
    app.earbuds.pnc_deadline = Some(Instant::now() + Duration::from_secs(5));
    app.send(Cmd::PncQuery);
}

fn pnc_test(app: &mut App) {
    let (l, r) = worn(app);
    if !(l && r) { app.earbuds.pnc_state = 5; return; }
    app.earbuds.pnc_state = 3;
    app.earbuds.pnc_deadline = Some(Instant::now() + Duration::from_secs(15));
    app.send(Cmd::Pnc(1));
}

fn pnc_finish(app: &mut App) {
    if app.earbuds.pnc_state == 3 { app.send(Cmd::Pnc(3)); }
    app.earbuds.pnc_state = 0;
    app.earbuds.pnc_deadline = None;
    app.earbuds.pnc_open = false;
}

fn fit_play(app: &mut App) {
    let (l, r) = worn(app);
    if !(l && r) { app.earbuds.fit_state = 1; return; }
    app.earbuds.fit_state = 2;
    app.earbuds.fit_started = true;
    app.earbuds.fit_shown = false;
    app.earbuds.fit_seq = app.snap.fit.2;
    app.earbuds.fit_deadline = Some(Instant::now() + Duration::from_secs(15));
    app.send(Cmd::FitTest(true));
}

fn fit_stop(app: &mut App) {
    if app.earbuds.fit_started { app.earbuds.fit_started = false; app.send(Cmd::FitTest(false)); }
    app.earbuds.fit_deadline = None;
}

/// Every frame: the results the buds sent and the timeouts of the fit test and Personalized ANC.
fn tick(app: &mut App) {
    let now = Instant::now();
    let s = app.snap.clone();
    // Fit test
    if app.earbuds.fit_open && app.earbuds.fit_state == 2 {
        if s.fit.2 != app.earbuds.fit_seq {
            app.earbuds.fit_seq = s.fit.2;
            let ok = |v: u8| matches!(v, 0 | 1 | 6);
            if !ok(s.fit.0) || !ok(s.fit.1) { app.earbuds.fit_state = 3; }
            else { app.earbuds.fit_shown = true; app.earbuds.fit_state = if s.fit.0 == 1 && s.fit.1 == 1 { 4 } else { 5 }; }
        }
        if app.earbuds.fit_deadline.is_some_and(|d| now >= d) { app.earbuds.fit_state = 3; }
    }
    // Personalized ANC
    if app.earbuds.pnc_open {
        if s.pnc.2 != app.earbuds.pnc_seq {
            app.earbuds.pnc_seq = s.pnc.2;
            let (kind, value) = (s.pnc.0, s.pnc.1);
            let st = app.earbuds.pnc_state;
            if kind == 1 && st == 0 { app.earbuds.pnc_state = if value != 0 { 1 } else { 2 }; app.earbuds.pnc_deadline = None; }
            if kind == 2 && st == 3 && value != 0 { app.earbuds.pnc_reason = 0; app.earbuds.pnc_state = 4; }
            if kind == 3 && st == 3 {
                if value == 0 { app.earbuds.pnc_state = 6; app.send(Cmd::StatusRead); } else { app.earbuds.pnc_reason = value; app.earbuds.pnc_state = 4; }
            }
        }
        if app.earbuds.pnc_deadline.is_some_and(|d| now >= d) {
            app.earbuds.pnc_deadline = None;
            match app.earbuds.pnc_state { 0 => app.earbuds.pnc_state = 2, 3 => { app.earbuds.pnc_reason = 0; app.earbuds.pnc_state = 4; } _ => {} }
        }
    }
}

fn dialogs(app: &mut App, ui: &mut Ui) {
    tick(app);
    if app.earbuds.fit_open || app.earbuds.pnc_open { ui.ctx().request_repaint_after(Duration::from_millis(250)); }
    let tr = app.tr.clone();
    let s = app.snap.clone();

    // Wear detection: auto play/pause (the buds) and smart auto-pause (local). One on turns the other off.
    if app.earbuds.wear_open {
        let auto = s.features.iter().find(|f| f.0 == FEATURE_AUTO_PLAY_PAUSE).is_some_and(|f| f.1 == 1);
        let smart = load_settings()["smart_auto_pause"].as_bool() == Some(true);
        let (mut flip_auto, mut flip_smart) = (false, false);
        let open = sheets::sheet(ui.ctx(), "wear", t(&tr, "wear_title"), Title::Centre, Vec2::new(sheets::WIDTH, 308.2), |ui, inner| {
            let sub = t(&tr, "wear_firmware_sub");
            let title = t(&tr, "wear_firmware_title");
            let h = sheets::row_height(ui, title, sub, inner.width());
            let r = Rect::from_min_size(inner.min, Vec2::new(inner.width(), h));
            flip_auto = sheets::row_at(ui, "wear-auto", r, title, sub, auto);
            let sub = t(&tr, "wear_smart_sub");
            let title = t(&tr, "wear_smart_title");
            let h = sheets::row_height(ui, title, sub, inner.width());
            let r = Rect::from_min_size(Pos2::new(inner.left(), r.bottom() + 8.0), Vec2::new(inner.width(), h));
            flip_smart = sheets::row_at(ui, "wear-smart", r, title, sub, smart);
        });
        if flip_auto {
            app.send(Cmd::Features(vec![(FEATURE_AUTO_PLAY_PAUSE, !auto)]));
            if !auto { save_setting("smart_auto_pause", false.into()); }
        }
        if flip_smart {
            save_setting("smart_auto_pause", (!smart).into());
            if !smart { app.send(Cmd::Features(vec![(FEATURE_AUTO_PLAY_PAUSE, false)])); }
        }
        if !open { app.earbuds.wear_open = false; }
    }

    // Find my earbuds: the warning before the loud tone.
    if app.earbuds.find_open {
        let warn = app.earbuds.find_warn;
        let playing = app.earbuds.find_playing;
        let title = t(&tr, if warn { "find_warn_title" } else { "find_title" });
        let body = t(&tr, if warn { "find_warn_msg" } else { "find_hint" });
        let primary = t(&tr, if playing { "find_stop" } else if warn { "find_warn_play" } else { "find_play" });
        let mut act = None;
        let open = sheets::sheet(ui.ctx(), "find", title, Title::Centre, Vec2::new(sheets::WIDTH, 211.8), |ui, inner| {
            let body_rect = Rect::from_min_max(inner.min + Vec2::new(0.0, 4.0), Pos2::new(inner.right(), inner.bottom() - 66.0));
            phone::paragraph(ui, body_rect, body, 14.0, glass::white(0.80), true);
            act = sheets::actions(ui, inner, "", primary);
        });
        match act {
            Some(true) => {
                if playing { app.send(Cmd::Find(false)); app.earbuds.find_playing = false; }
                else if !warn && { let (l, r) = worn(app); l || r } { app.earbuds.find_warn = true; }
                else { app.earbuds.find_warn = false; app.earbuds.find_playing = true; app.send(Cmd::Find(true)); }
            }
            _ => {}
        }
        if !open { app.earbuds.find_open = false; if playing { app.send(Cmd::Find(false)); app.earbuds.find_playing = false; } }
    }

    // Fit test: the title follows the state; two 120 dp result columns show a result per bud.
    if app.earbuds.fit_open {
        let state = app.earbuds.fit_state as usize;
        let title = t(&tr, ["fit_title", "fit_insert", "fit_keep", "fit_failed", "fit_perfect", "fit_adjust"][state.min(5)]);
        let body = if state == 2 || state == 4 { String::new() } else if state == 5 {
            let who = if s.fit.0 != 1 && s.fit.1 != 1 { "fit_both" } else if s.fit.0 != 1 { "fit_left" } else { "fit_right" };
            t(&tr, "fit_adjust_tips").replace("%1$s", t(&tr, who))
        } else { t(&tr, "fit_hint").to_string() };
        let primary = t(&tr, match state { 2 => "fit_playing", 4 => "gesture_done", 5 => "fit_again", _ => "fit_play" });
        let shown = app.earbuds.fit_shown;
        let word = |v: u8| match v { 1 => t(&tr, "fit_good"), 0 => t(&tr, "fit_average"), 6 => t(&tr, "fit_poor"), _ => "" };
        let height = if shown { 300.0 } else { 246.1 };
        let mut act = None;
        let open = sheets::sheet(ui.ctx(), "fit", title, Title::Centre, Vec2::new(sheets::WIDTH, height), |ui, inner| {
            if !body.is_empty() {
                let body_rect = Rect::from_min_max(inner.min + Vec2::new(0.0, 4.0), Pos2::new(inner.right(), inner.min.y + 76.0));
                phone::paragraph(ui, body_rect, &body, 14.0, glass::white(0.80), true);
            }
            if shown {
                let y = inner.bottom() - 104.0;
                for (i, label) in [t(&tr, "status_left"), t(&tr, "status_right")].iter().enumerate() {
                    let x = inner.center().x + (i as f32 - 0.5) * 130.0;
                    let value = word(if i == 0 { s.fit.0 } else { s.fit.1 });
                    ui.painter().text(Pos2::new(x, y), Align2::CENTER_CENTER, format!("{label} · {value}"), egui::FontId::proportional(14.0), glass::white(0.86));
                }
            }
            act = sheets::actions(ui, inner, "", primary);
        });
        match act {
            Some(true) => {
                if state == 4 { fit_stop(app); app.earbuds.fit_state = 0; app.earbuds.fit_shown = false; app.earbuds.fit_open = false; }
                else if state != 2 { fit_play(app); }
            }
            _ => {}
        }
        if !open { fit_stop(app); app.earbuds.fit_state = 0; app.earbuds.fit_shown = false; app.earbuds.fit_open = false; }
    }

    // Firmware: HeyMelody updates it; a failed flash can leave the buds unusable.
    if app.earbuds.firmware_open {
        let mut act = None;
        let open = sheets::sheet(ui.ctx(), "firmware", t(&tr, "firmware_dialog_title"), Title::Centre, Vec2::new(sheets::WIDTH, 246.1), |ui, inner| {
            let body_rect = Rect::from_min_max(inner.min + Vec2::new(0.0, 4.0), Pos2::new(inner.right(), inner.bottom() - 66.0));
            phone::paragraph(ui, body_rect, t(&tr, "firmware_dialog_body"), 14.0, glass::white(0.80), true);
            act = sheets::actions(ui, inner, "", t(&tr, "dialog_close"));
        });
        if act.is_some() || !open { app.earbuds.firmware_open = false; }
    }

    // Personalized ANC: the flow keeps its own state; only its drawing changed.
    if app.earbuds.pnc_open {
        let st = app.earbuds.pnc_state as usize;
        let title = t(&tr, ["pnc_title", "pnc_stored_title", "pnc_test_title", "pnc_title", "pnc_failed", "pnc_title", "pnc_title"][st.min(6)]);
        let ri = if app.earbuds.pnc_reason <= 5 { app.earbuds.pnc_reason as usize } else { 0 };
        let reason = ["", "pnc_fail_quiet", "pnc_fail_fit", "pnc_fail_wind", "pnc_fail_still", "pnc_fail_audio"][ri];
        let body = match st {
            0 => t(&tr, "conn_connecting"),
            1 => t(&tr, "pnc_stored_body"),
            2 => t(&tr, "pnc_test_body"),
            3 => t(&tr, "pnc_testing"),
            4 => if reason.is_empty() { "" } else { t(&tr, reason) },
            5 => t(&tr, "pnc_wear"),
            _ => t(&tr, "pnc_done"),
        };
        let left = t(&tr, if st == 1 { "pnc_test_again" } else if st == 6 { "dialog_close" } else { "dialog_cancel" });
        let primary = if matches!(st, 1 | 2 | 4 | 5) { t(&tr, match st { 1 => "pnc_use", 2 => "pnc_start", _ => "pnc_retry" }) } else { "" };
        let mut act = None;
        let open = sheets::sheet(ui.ctx(), "pnc", title, Title::Centre, Vec2::new(sheets::WIDTH, 300.0), |ui, inner| {
            let body_rect = Rect::from_min_max(inner.min + Vec2::new(0.0, 4.0), Pos2::new(inner.right(), inner.bottom() - 66.0));
            phone::paragraph(ui, body_rect, body, 14.0, glass::white(0.80), true);
            act = sheets::actions(ui, inner, left, primary);
        });
        match act {
            Some(false) => if st == 1 { pnc_test(app); } else { pnc_finish(app); },
            Some(true) => match st {
                1 => { app.send(Cmd::Pnc(2)); app.earbuds.pnc_state = 0; app.earbuds.pnc_open = false; }
                5 => pnc_start(app),
                _ => pnc_test(app),
            },
            None => {}
        }
        if !open { pnc_finish(app); }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The model's confirmed list shows the Wear row before the buds' feature read; an unknown model shows none.
    #[test]
    fn wear_row_follows_the_model_before_a_read() {
        let buds = crate::types::models().iter().find(|m| m["name"].as_str() == Some("OnePlus Buds 4"));
        let s = Snapshot { model: buds, ..Default::default() };
        assert!(wear_offered(&s));
        assert!(!wear_offered(&Snapshot::default()));
    }

    /// Game equalizer: the row needs the buds' feature `21` and their `0x0420` accept; the switch shows its value.
    #[test]
    fn game_eq_needs_the_feature_and_the_command() {
        let listed = crate::types::Caps(Some(vec![CMD_SET_FEATURE, CMD_GAME_EQ_STATE]));
        let s = Snapshot { caps: listed, features: vec![(FEATURE_GAME_EQ, 1)], ..Default::default() };
        assert_eq!(game_eq_row(&s), Some(true));
        // The buds do not accept `0x0420`: no row, as on the phone.
        let s = Snapshot { caps: crate::types::Caps(Some(vec![CMD_SET_FEATURE])), features: vec![(FEATURE_GAME_EQ, 1)], ..Default::default() };
        assert_eq!(game_eq_row(&s), None);
        // The buds do not list the feature: no row.
        let s = Snapshot { caps: crate::types::Caps(Some(vec![CMD_GAME_EQ_STATE])), ..Default::default() };
        assert_eq!(game_eq_row(&s), None);
    }

    /// The row's switch writes the feature and the game state, as the phone's switch and watcher do.
    #[test]
    fn game_eq_switch_writes_its_state() {
        let s = Snapshot { features: vec![(FEATURE_GAME_EQ, 0)], ..Default::default() };
        assert!(matches!(home_logic::feature_cmds(&s, FEATURE_GAME_EQ, true).as_slice(), [Cmd::GameEq(true)]));
        assert!(matches!(home_logic::feature_cmds(&s, FEATURE_GAME_EQ, false).as_slice(), [Cmd::GameEq(false)]));
    }
}
