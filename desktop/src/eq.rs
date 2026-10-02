//! The Equalizer page (`EqActivity`): built-in and custom presets, the band editor, BassWave.
//! The page shows the buds' state; every write is re-read by the session.

use crate::protocol::*;
use crate::session::{Cmd, Snapshot, Status};
use crate::{t, with_app, App, Eq, EqRow, MainWindow};
use slint::{ComponentHandle, Model, ModelRc, VecModel};

fn send(a: &App, c: Cmd) { let _ = a.tx.send(c); }

/// The preset in the editor: the selected one, if it is custom (`0x0418` selects and saves in one frame,
/// so only the selected preset can be edited).
fn editing(s: &Snapshot) -> Option<&Preset> { s.eq_custom.iter().find(|p| Some(p.id) == s.eq_current) }

fn freq_label(f: u16) -> String {
    match f {
        f if f >= 1000 && f % 1000 == 0 => format!("{}k", f / 1000),
        f if f >= 1000 => format!("{:.1}k", f as f32 / 1000.0),
        f => f.to_string(),
    }
}

/// A smooth curve through the band points (Catmull-Rom as cubic Béziers) in a `w` x `h` plot, flat to the edges.
pub fn curve(gains: &[i32], (w, h): (f32, f32)) -> String {
    let n = gains.len();
    if n == 0 || w <= 0.0 || h <= 0.0 { return String::new(); }
    let p: Vec<(f32, f32)> = gains.iter().enumerate()
        .map(|(i, g)| ((i as f32 + 0.5) / n as f32 * w, (EQ_GAIN - g) as f32 / (2 * EQ_GAIN) as f32 * h)).collect();
    let mut d = format!("M 0 {} L {} {}", p[0].1, p[0].0, p[0].1);
    for i in 0..n - 1 {
        let (p0, p1, p2, p3) = (p[i.saturating_sub(1)], p[i], p[i + 1], p[(i + 2).min(n - 1)]);
        let c1 = (p1.0 + (p2.0 - p0.0) / 6.0, p1.1 + (p2.1 - p0.1) / 6.0);
        let c2 = (p2.0 - (p3.0 - p1.0) / 6.0, p2.1 - (p3.1 - p1.1) / 6.0);
        d += &format!(" C {} {} {} {} {} {}", c1.0, c1.1, c2.0, c2.1, p2.0, p2.1);
    }
    d + &format!(" L {w} {}", p[n - 1].1)
}

pub fn setup(main: &MainWindow) {
    let eq = main.global::<Eq>();
    eq.on_select_builtin(|id| with_app(|a| send(a, Cmd::EqBuiltIn(id as u8))));
    eq.on_select_custom(|id| with_app(|a| {
        if let Some(p) = a.snap.eq_custom.iter().find(|p| p.id == id as u8) { send(a, Cmd::EqSave(p.clone())); }
    }));
    eq.on_add(|| with_app(|a| {
        let name = (1..=9).map(|i| format!("Custom{i}")).find(|n| a.snap.eq_custom.iter().all(|p| &p.name != n));
        let Some(name) = name else { return };
        // The buds' own presets' bands if they have any, else the model's.
        let freqs = a.snap.eq_custom.first().map(|p| p.freqs.clone()).unwrap_or_else(|| eq_model_freqs(a.snap.model));
        send(a, Cmd::EqCreate(Preset::new(&name, freqs)));
    }));
    eq.on_delete(|| with_app(|a| {
        let Some(p) = editing(&a.snap).cloned() else { return };
        // The buds refuse to delete the preset in use: switch to a recommended one first.
        if let Some((id, _)) = eq_builtins(a.snap.model, a.snap.firmware.as_deref()).first() {
            send(a, Cmd::EqBuiltIn(*id));
        }
        send(a, Cmd::EqDelete(p));
    }));
    eq.on_rename(|name| with_app(|a| {
        let Some(mut p) = editing(&a.snap).cloned() else { return };
        // ponytail: 20-byte cap is the phone app's, not a measured firmware limit (the length is one byte).
        let mut name = name.trim().to_string();
        while name.len() > 20 { name.pop(); }
        if name.is_empty() || name == p.name { return; }
        p.name = name;
        send(a, Cmd::EqSave(p));
    }));
    eq.on_band_moved(|i, g| with_app(|a| {
        a.gains.set_row_data(i as usize, g);
        redraw(a);
    }));
    eq.on_plot_resized(|w, h| with_app(|a| { a.plot = (w, h); redraw(a); }));
    // Each release saves the whole preset, like HeyMelody.
    eq.on_band_released(|i, g| with_app(|a| {
        let Some(mut p) = editing(&a.snap).cloned() else { return };
        p.gains[i as usize] = g as i8;
        send(a, Cmd::EqSave(p));
    }));
    eq.on_bass_toggled(|on| with_app(|a| send(a, Cmd::BassWave(on))));
    eq.on_bass_released(|v| with_app(|a| send(a, Cmd::BassLevel(v as i8))));
}

/// The curve, and in the Dot matrix style its dots (drawn at physical pixels, the plot is logical).
pub fn redraw(a: &App) {
    let d = curve(&a.gains.iter().collect::<Vec<_>>(), a.plot);
    let (dots_on, scale) = crate::STYLE.get();
    let accent = a.main.global::<crate::Palette>().get_accent();
    let eq = a.main.global::<Eq>();
    eq.set_curve_dots(if dots_on { crate::dots::curve(&d, a.plot, accent, scale) } else { Default::default() });
    eq.set_curve(d.into());
}

pub fn apply(a: &mut App) {
    let s = &a.snap;
    let eq = a.main.global::<Eq>();
    let has_custom = eq_has_custom(s.model, &s.caps);
    let builtins: Vec<EqRow> = if s.caps.supports(CMD_SET_EQ) {
        eq_builtins(s.model, s.firmware.as_deref()).into_iter()
            .map(|(id, key)| EqRow { id: id as i32, name: t(&a.tr, key).into() }).collect()
    } else { Vec::new() };
    let customs: Vec<EqRow> = s.eq_custom.iter().map(|p| EqRow { id: p.id as i32, name: p.name.as_str().into() }).collect();
    eq.set_builtins(ModelRc::new(VecModel::from(builtins)));
    eq.set_can_add(s.status == Status::On && has_custom && customs.len() < eq_max_custom(s.model));
    eq.set_customs(ModelRc::new(VecModel::from(customs)));
    eq.set_current(s.eq_current.map_or(-1, |c| c as i32));
    eq.set_has_custom(has_custom);
    eq.set_has_bass(eq_has_bass(s.model, &s.caps));
    eq.set_bass_on(s.bass_on.unwrap_or(false));

    // Never under a finger: a re-read landing mid-drag would yank the knob back.
    let dragging = eq.get_dragging();
    if !dragging { eq.set_bass_level(s.bass_level.unwrap_or(0) as i32); }

    let edit = editing(s).cloned();
    eq.set_editing(edit.is_some());
    let Some(p) = edit else { a.edit_key = None; return };
    // The name field is refilled only when the preset or its saved name changes, not while typing.
    let key = (p.id, p.name.clone());
    if a.edit_key.as_ref() != Some(&key) {
        eq.set_edit_name(p.name.as_str().into());
        a.edit_key = Some(key);
    }
    eq.set_freq_labels(ModelRc::new(VecModel::from(p.freqs.iter().map(|&f| freq_label(f).into()).collect::<Vec<slint::SharedString>>())));
    let gains: Vec<i32> = p.gains.iter().map(|&g| g as i32).collect();
    if !dragging && a.gains.iter().collect::<Vec<_>>() != gains {
        a.gains.set_vec(gains);
        redraw(a);
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn curve_and_labels() {
        assert_eq!(super::freq_label(62), "62");
        assert_eq!(super::freq_label(16000), "16k");
        let d = super::curve(&[0, 6], (1000.0, 120.0));
        assert!(d.starts_with("M 0 60 L 250 60 C") && d.ends_with("L 1000 0"), "{d}");
    }
}
