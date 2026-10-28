//! The Equalizer page (`EqActivity`): recommended and custom presets, the band editor, BassWave.
//! The page shows the buds' state; every write is re-read by the session. A click shows at once, like the phone.

use crate::glass::{self, white};
use crate::session::{Cmd, Snapshot, Status};
use crate::types::{self, Preset, CMD_SET_EQ, EQ_GAIN};
use crate::{icons, t, App};
use eframe::egui::{self, Align2, Pos2, Rect, Shape, Stroke, Ui, Vec2};
use std::time::{Duration, Instant};

/// How long a create or delete shows before the buds' re-read confirms it.
const HOLD: Duration = Duration::from_secs(4);
const ROW: f32 = 56.0;
const GAP: f32 = 10.0;
const MAX_W: f32 = 760.0;

#[derive(Default)]
pub struct State {
    added: Vec<(String, Instant)>,
    deleted: Vec<(u8, Instant)>,
    /// The preset last clicked; it shows until the buds report it.
    picked: Option<(u8, Instant)>,
    /// The band gains under a finger (and until the buds echo them).
    drag: Option<Vec<f32>>,
    bass: Option<f32>,
    /// The BassWave band levels under a finger (low, medium, high).
    band_drag: [Option<f32>; 3],
    name: String,
    name_key: Option<(u8, String)>,
    confirm_delete: bool,
}

/// The preset in the editor: the selected one, if it is custom (`0x0418` selects and saves in one frame).
fn editing(s: &Snapshot) -> Option<&Preset> { s.eq_custom.iter().find(|p| Some(p.id) == s.eq_current) }

fn freq_label(f: u16) -> String {
    match f {
        f if f >= 1000 && f % 1000 == 0 => format!("{}k", f / 1000),
        f if f >= 1000 => format!("{:.1}k", f as f32 / 1000.0),
        f => f.to_string(),
    }
}

fn label(ui: &Ui, x: f32, y: f32, s: &str) {
    glass::text(ui, Pos2::new(x + 6.0, y + 14.0), Align2::LEFT_CENTER, s, 14.0, 0.72);
}

/// Draws the page from `body` (already scrolled); returns the bottom of its content.
pub fn show(app: &mut App, ui: &mut Ui, body: Rect) -> f32 {
    let s = app.snap.clone();
    let tr = app.tr.clone();
    // Wide window: the presets on the left, the band editor on the right.
    let two = body.width() >= 900.0;
    let w = if two { ((body.width() - 14.0) / 2.0).floor() } else { body.width().min(MAX_W) };
    let x0 = body.left();
    let mut y = body.top();
    if s.status != Status::On {
        let r = Rect::from_min_size(Pos2::new(x0, y), Vec2::new(w, 96.0));
        glass::flat_at(ui, r, 32.0, 1.0);
        glass::text(ui, r.center(), Align2::CENTER_CENTER, t(&tr, "eq_not_connected"), 15.0, 0.8);
        return r.bottom();
    }

    let st = &mut app.eq;
    st.added.retain(|(n, at)| at.elapsed() < HOLD && s.eq_custom.iter().all(|p| &p.name != n));
    st.deleted.retain(|(id, at)| at.elapsed() < HOLD && s.eq_custom.iter().any(|p| p.id == *id));
    st.picked = st.picked.filter(|(id, at)| at.elapsed() < HOLD && s.eq_current != Some(*id));
    let gone = |st: &State, id: u8| st.deleted.iter().any(|(d, _)| *d == id);
    let current = st.picked.map(|(id, _)| id).or(s.eq_current).filter(|&c| !gone(st, c));
    let mut sends: Vec<Cmd> = vec![];

    // Recommended
    let builtins = if s.caps.supports(CMD_SET_EQ) { types::eq_builtins(s.model, s.firmware.as_deref()) } else { vec![] };
    if !builtins.is_empty() {
        label(ui, x0, y, t(&tr, "eq_recommended"));
        y += 34.0;
        let card = Rect::from_min_size(Pos2::new(x0, y), Vec2::new(w, (ROW + GAP) * builtins.len() as f32 - GAP));
        for (i, (id, key)) in builtins.iter().enumerate() {
            let r = Rect::from_min_size(Pos2::new(x0, y + (ROW + GAP) * i as f32), Vec2::new(w, ROW));
            glass::flat_at(ui, r, 28.0, 1.0);
            if glass::list_item_at(ui, ("builtin", *id), r, t(&tr, key), current == Some(*id), false).clicked() {
                sends.push(Cmd::EqBuiltIn(*id));
                st.picked = Some((*id, Instant::now()));
            }
        }
        y += card.height() + 18.0;
    }

    // BassWave
    if types::eq_has_bass(s.model, &s.caps) {
        let on = s.bass_on.unwrap_or(false);
        // realme dynamic sound: a label over each slider; the band byte is 1 low, 2 medium, 3 high.
        let bands = types::eq_bass_bands(s.model);
        let panel = if on { if bands { 64.0 * 3.0 + 12.0 } else { 60.0 } } else { 0.0 };
        let h = ROW + 16.0 + panel;
        let card = Rect::from_min_size(Pos2::new(x0, y), Vec2::new(w, h));
        glass::flat_at(ui, card, 28.0, 1.0);
        let row = Rect::from_min_size(card.min, Vec2::new(w, ROW + 16.0));
        let (_, toggled) = glass::row_at(ui, "bass", row, icons::EQUALIZER, t(&tr, "eq_basswave"), t(&tr, "eq_basswave_sub"), glass::Trailing::Switch(on), true);
        if toggled { sends.push(Cmd::BassWave(!on)); }
        if on {
            if bands {
                for (i, key) in ["eq_bass_low", "eq_bass_mid", "eq_bass_high"].iter().enumerate() {
                    let top = row.bottom() - 4.0 + 64.0 * i as f32;
                    glass::text(ui, Pos2::new(x0 + 24.0, top + 10.0), Align2::LEFT_CENTER, t(&tr, key), 12.5, 0.74);
                    let sr = Rect::from_min_size(Pos2::new(x0 + 24.0, top + 20.0), Vec2::new(w - 90.0, 44.0));
                    let mut v = st.band_drag[i].unwrap_or(s.bass_bands[i].unwrap_or(0) as f32);
                    let sl = glass::slider_at(ui, ("bassb", i), sr, &mut v, -5.0, 5.0, true);
                    glass::text(ui, Pos2::new(sr.right() + 16.0, sr.center().y), Align2::LEFT_CENTER, &format!("{:+}", v as i32), 15.0, 1.0);
                    if sl.changed { st.band_drag[i] = Some(v); }
                    if sl.released { sends.push(Cmd::BassLevel(v as i8, i as u8 + 1)); st.band_drag[i] = None; }
                }
            } else {
                let mut v = st.bass.unwrap_or(s.bass_level.unwrap_or(0) as f32);
                let sr = Rect::from_min_size(Pos2::new(x0 + 24.0, row.bottom() - 4.0), Vec2::new(w - 90.0, 44.0));
                let sl = glass::slider_at(ui, "bassl", sr, &mut v, -5.0, 5.0, true);
                glass::text(ui, Pos2::new(sr.right() + 16.0, sr.center().y), Align2::LEFT_CENTER, &format!("{:+}", v as i32), 15.0, 1.0);
                if sl.changed { st.bass = Some(v); }
                if sl.released { sends.push(Cmd::BassLevel(v as i8, 0)); st.bass = None; }
            }
        }
        y += h + 18.0;
    }

    // Custom
    let has_custom = types::eq_has_custom(s.model, &s.caps);
    if has_custom {
        label(ui, x0, y, t(&tr, "eq_custom"));
        y += 34.0;
        let customs: Vec<&Preset> = s.eq_custom.iter().filter(|p| !gone(st, p.id)).collect();
        let n = customs.len() + st.added.len();
        if n > 0 {
            let card = Rect::from_min_size(Pos2::new(x0, y), Vec2::new(w, (ROW + GAP) * n as f32 - GAP));
            for (i, p) in customs.iter().enumerate() {
                let r = Rect::from_min_size(Pos2::new(x0, y + (ROW + GAP) * i as f32), Vec2::new(w, ROW));
                glass::flat_at(ui, r, 28.0, 1.0);
                if glass::list_item_at(ui, ("custom", p.id), r, &p.name, current == Some(p.id), false).clicked() {
                    sends.push(Cmd::EqSave((*p).clone()));
                    st.picked = Some((p.id, Instant::now()));
                }
                glass::icon_at(ui, icons::PENCIL, Rect::from_center_size(Pos2::new(r.right() - 32.0, r.center().y), Vec2::splat(20.0)), white(0.7));
            }
            for (j, (name, _)) in st.added.iter().enumerate() {
                let r = Rect::from_min_size(Pos2::new(x0, y + (ROW + GAP) * (customs.len() + j) as f32), Vec2::new(w, ROW));
                glass::flat_at(ui, r, 28.0, 1.0);
                glass::list_item_at(ui, ("pending", j), r, name, false, true);
            }
            y += card.height() + 12.0;
        }
        let can_add = n < types::eq_max_custom(s.model);
        let new = Rect::from_min_size(Pos2::new(x0, y), Vec2::new(130.0, 46.0));
        let r = glass::button_at(ui, "new", new, 23.0, false);
        let a = if can_add { 1.0 } else { 0.4 };
        glass::icon_at(ui, icons::PLUS, Rect::from_center_size(Pos2::new(new.left() + 28.0, new.center().y), Vec2::splat(20.0)), white(a));
        glass::text(ui, Pos2::new(new.left() + 48.0, new.center().y), Align2::LEFT_CENTER, t(&tr, "eq_add"), 15.0, a);
        if r.clicked() && can_add {
            // A name sent in the last seconds is taken too: two quick clicks must not make two of one name.
            let name = (1..=9).map(|i| format!("Custom{i}")).find(|n| s.eq_custom.iter().all(|p| &p.name != n) && st.added.iter().all(|(m, _)| m != n));
            if let Some(name) = name {
                let freqs = s.eq_custom.first().map(|p| p.freqs.clone()).unwrap_or_else(|| types::eq_model_freqs(s.model));
                sends.push(Cmd::EqCreate(Preset::new(&name, freqs)));
                st.added.push((name, Instant::now()));
            }
        }
        y += 46.0 + 18.0;
    }

    // The editor of the selected custom preset
    if let Some(p) = editing(&s).filter(|p| Some(p.id) == current) {
        let (ex, ey, ew) = if two { (x0 + w + 14.0, body.top(), body.width() - w - 14.0) } else { (x0, y, w) };
        let h = if two { (body.bottom() - body.top()).max(364.0) } else { 64.0 + 230.0 + 70.0 };
        // The bands fill the card between the name field and the Delete button.
        let ch = (h - 76.0 - 80.0 - 56.0).clamp(170.0, 340.0);
        let card = Rect::from_min_size(Pos2::new(ex, ey), Vec2::new(ew, h));
        glass::flat_at(ui, card, 28.0, 1.0);
        // The name field is refilled only when the preset or its saved name changes, not while typing.
        let key = (p.id, p.name.clone());
        if st.name_key.as_ref() != Some(&key) { st.name = p.name.clone(); st.name_key = Some(key); }
        let nf = Rect::from_min_size(Pos2::new(ex + 20.0, ey + 16.0), Vec2::new(ew - 40.0, 44.0));
        let resp = glass::text_field_at(ui, nf, &mut st.name, 20);
        if resp.lost_focus() {
            let name = st.name.trim().to_string();
            if !name.is_empty() && name != p.name {
                let mut q = p.clone();
                q.name = name;
                sends.push(Cmd::EqSave(q));
            }
        }
        // Bands
        let n = p.gains.len().max(1);
        let shown: Vec<f32> = match &st.drag { Some(d) if d.len() == p.gains.len() => d.clone(), _ => p.gains.iter().map(|g| *g as f32).collect() };
        let mut gains = shown.clone();
        let band_top = ey + 76.0;
        let mut pts: Vec<Pos2> = vec![];
        let mut release = false;
        for i in 0..n {
            let cx = ex + 20.0 + (ew - 40.0) * (i as f32 + 0.5) / n as f32;
            let col = Rect::from_center_size(Pos2::new(cx, band_top + ch / 2.0), Vec2::new(50.0, ch));
            let sl = glass::vslider_at(ui, ("band", i), col, &mut gains[i], -(EQ_GAIN as f32), EQ_GAIN as f32, true);
            if sl.changed { st.drag = Some(gains.clone()); }
            if sl.released { release = true; }
            let t01 = (gains[i] + EQ_GAIN as f32) / (2 * EQ_GAIN) as f32;
            pts.push(Pos2::new(cx, col.bottom() - 12.0 - (col.height() - 24.0) * t01));
            glass::text(ui, Pos2::new(cx, band_top + ch + 18.0), Align2::CENTER_CENTER, &freq_label(p.freqs[i]), 12.5, 0.72);
            glass::text(ui, Pos2::new(cx, band_top - 8.0), Align2::CENTER_CENTER, &format!("{:+}", gains[i] as i32), 12.5, 0.9);
        }
        smooth_line(ui, &pts);
        if release {
            let mut q = p.clone();
            for (g, v) in q.gains.iter_mut().zip(&gains) { *g = *v as i8; }
            sends.push(Cmd::EqSave(q));
            st.drag = Some(gains.clone());
        }
        // The buds echo the gains: the finger's copy is not needed any more.
        if let Some(d) = &st.drag { if !release && d.iter().zip(&p.gains).all(|(a, b)| *a as i8 == *b) { st.drag = None; } }
        // Delete
        let del = Rect::from_min_size(Pos2::new(ex + 20.0, card.bottom() - 62.0), Vec2::new(140.0, 46.0));
        if glass::button_at(ui, "delete", del, 23.0, false).clicked() { st.confirm_delete = true; }
        glass::icon_at(ui, icons::DELETE, Rect::from_center_size(Pos2::new(del.left() + 28.0, del.center().y), Vec2::splat(20.0)), white(1.0));
        glass::text(ui, Pos2::new(del.left() + 48.0, del.center().y), Align2::LEFT_CENTER, t(&tr, "eq_delete"), 15.0, 1.0);
        if !two { y += h + 18.0; }

        if st.confirm_delete {
            let msg = t(&tr, "eq_delete_confirm").replace("%1$s", &p.name);
            let mut decision = None;
            let open = glass::dialog(ui.ctx(), "eqdel", t(&tr, "eq_delete"), Vec2::new(420.0, 210.0), |ui| {
                ui.add(egui::Label::new(egui::RichText::new(&msg).size(15.0).color(white(0.9))).wrap());
                decision = glass::dialog_buttons(ui, t(&tr, "dialog_cancel"), t(&tr, "eq_delete"));
            });
            match decision {
                Some(true) => {
                    // The buds refuse to delete the preset in use: switch to a recommended one first.
                    if let Some((id, _)) = builtins.first() { sends.push(Cmd::EqBuiltIn(*id)); }
                    st.deleted.push((p.id, Instant::now()));
                    sends.push(Cmd::EqDelete(p.clone()));
                    st.confirm_delete = false;
                }
                Some(false) => st.confirm_delete = false,
                None => st.confirm_delete = open,
            }
        }
    } else {
        st.confirm_delete = false;
    }

    for c in sends { app.send(c); }
    if two { y.max(body.top() + 364.0) } else { y }
}

/// A smooth line through the band points (Catmull-Rom, sampled).
fn smooth_line(ui: &Ui, p: &[Pos2]) {
    if p.len() < 2 { return; }
    let mut out = vec![];
    for i in 0..p.len() - 1 {
        let (p0, p1, p2, p3) = (p[i.saturating_sub(1)], p[i], p[i + 1], p[(i + 2).min(p.len() - 1)]);
        for k in 0..12 {
            let t = k as f32 / 12.0;
            let (t2, t3) = (t * t, t * t * t);
            let f = |a: f32, b: f32, c: f32, d: f32| 0.5 * ((2.0 * b) + (-a + c) * t + (2.0 * a - 5.0 * b + 4.0 * c - d) * t2 + (-a + 3.0 * b - 3.0 * c + d) * t3);
            out.push(Pos2::new(f(p0.x, p1.x, p2.x, p3.x), f(p0.y, p1.y, p2.y, p3.y)));
        }
    }
    out.push(*p.last().unwrap());
    ui.painter().add(Shape::line(out, Stroke::new(2.5, white(0.55))));
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    /// The page shows three sliders only for `bassBands` 3. Other models keep one slider.
    #[test]
    fn three_bass_sliders_only_for_bass_bands_3() {
        assert!(crate::types::eq_bass_bands(Some(&json!({ "bassBands": 3 }))));
        assert!(!crate::types::eq_bass_bands(Some(&json!({ "bassBands": 1 }))));
        assert!(!crate::types::eq_bass_bands(Some(&json!({}))));
        assert!(!crate::types::eq_bass_bands(None));
    }
}
