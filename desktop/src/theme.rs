//! Themes, colors & styles, as on the phone (`ui/Palette.kt`): three built-in presets read from the phone's
//! `values/themes.xml`, an accent per built-in, up to three custom presets of six colours, and Match system.

use crate::{load_settings, save_setting, t, with_app, App, Pal, Palette, Theme};
use serde_json::{json, Value};
use slint::{Color, ComponentHandle, ModelRc, SharedString, VecModel};
use std::time::{Duration, Instant};

const THEMES: &str = include_str!("../../app/src/main/res/values/themes.xml");
const COLORS: &str = include_str!("../../app/src/main/res/values/colors.xml");
const OLED: &str = "oled";
const DARK: &str = "dark";
const WHITE: &str = "white";
const BUILT_IN: [&str; 3] = [OLED, DARK, WHITE];
const MAX_CUSTOM: usize = 3;
const MAX_NAME: usize = 24;
const MAX_RECENT: usize = 5;
/// The desktop's look before presets: a first start keeps it.
const DEFAULT: &str = DARK;

/// The first `name` colour item after byte `from` (`<item name="x">#RRGGBB</item>`, `<color name="x">`).
fn xml_color(xml: &str, from: usize, name: &str) -> Color {
    let key = format!("name=\"{name}\">");
    let at = from + xml[from..].find(&key).expect(name) + key.len();
    parse_hex(&xml[at..at + 7]).expect(name)
}

pub fn hex(c: Color) -> String { format!("#{:02X}{:02X}{:02X}", c.red(), c.green(), c.blue()) }

pub fn parse_hex(s: &str) -> Option<Color> {
    let t = s.trim().trim_start_matches('#');
    if t.len() != 6 { return None; }
    u32::from_str_radix(t, 16).ok().map(|v| Color::from_argb_encoded(0xFF000000 | v))
}

/// WCAG 2 relative luminance.
fn luminance(c: Color) -> f64 {
    let ch = |v: u8| { let s = v as f64 / 255.0; if s <= 0.03928 { s / 12.92 } else { ((s + 0.055) / 1.055).powf(2.4) } };
    0.2126 * ch(c.red()) + 0.7152 * ch(c.green()) + 0.0722 * ch(c.blue())
}

/// WCAG 2 contrast ratio, 1..21.
pub fn contrast(a: Color, b: Color) -> f64 {
    let (la, lb) = (luminance(a), luminance(b));
    (la.max(lb) + 0.05) / (la.min(lb) + 0.05)
}

/// `from` moved `t` of the way to `to` (the phone's `Palette.blend`).
fn blend(from: Color, to: Color, t: f32) -> Color {
    let m = |a: u8, b: u8| (a as f32 + (b as f32 - a as f32) * t) as u8;
    Color::from_rgb_u8(m(from.red(), to.red()), m(from.green(), to.green()), m(from.blue(), to.blue()))
}

fn is_light(p: &Pal) -> bool { luminance(p.bg) > 0.5 }

/// Label on an accent fill: the lighter of text and background when it reaches 3:1, else the one that reads better.
fn on_accent(p: &Pal) -> Color {
    let (light, dark) = if luminance(p.text) > luminance(p.bg) { (p.text, p.bg) } else { (p.bg, p.text) };
    if contrast(light, p.accent) >= 3.0 || contrast(light, p.accent) >= contrast(dark, p.accent) { light } else { dark }
}

/// Switch track: the outline, moved toward the text (more on a light theme, where the outline is too faint).
pub fn track(p: &Pal) -> Color { blend(p.outline, p.text, if is_light(p) { 0.14 } else { 0.06 }) }

pub fn tokens(p: &Pal) -> [Color; 6] { [p.bg, p.card, p.accent, p.text, p.text2, p.outline] }

fn with_token(p: &Pal, i: usize, c: Color) -> Pal {
    let mut p = p.clone();
    *[&mut p.bg, &mut p.card, &mut p.accent, &mut p.text, &mut p.text2, &mut p.outline][i.min(5)] = c;
    p
}

fn name_key(id: &str) -> &'static str {
    match id { OLED => "theme_oled_black", DARK => "theme_classic_dark", _ => "theme_white" }
}

/// A built-in preset from its style in themes.xml, with the user's accent if one is set.
fn built_in(id: &str, tr: &[String]) -> Pal {
    let style = match id { OLED => "Theme.App.OLED", DARK => "Theme.App.Dark", _ => "Theme.App.Light" };
    let at = THEMES.find(&format!("<style name=\"{style}\"")).expect(style);
    let c = |n| xml_color(THEMES, at, n);
    let accent = load_settings()["palette_accent"][id].as_str().and_then(parse_hex);
    Pal {
        id: id.into(), name: t(tr, name_key(id)).into(), bg: c("appColorBg"), card: c("appColorCard"),
        accent: accent.unwrap_or(c("appColorAccent")), text: c("appColorTextPrimary"),
        text2: c("appColorTextSecondary"), outline: c("appColorOutline"),
    }
}

fn to_json(p: &Pal) -> Value {
    let k = ["background", "card", "accent", "text", "textSecondary", "outline"];
    let mut o = json!({ "id": p.id.as_str(), "name": p.name.as_str() });
    for (k, c) in k.iter().zip(tokens(p)) { o[k] = hex(c).into(); }
    o
}

fn from_json(o: &Value) -> Option<Pal> {
    let c = |k: &str| o[k].as_str().and_then(parse_hex);
    Some(Pal {
        id: o["id"].as_str()?.into(), name: o["name"].as_str()?.into(), bg: c("background")?, card: c("card")?,
        accent: c("accent")?, text: c("text")?, text2: c("textSecondary")?, outline: c("outline")?,
    })
}

fn custom() -> Vec<Pal> {
    load_settings()["palette_custom"].as_array().map(|a| a.iter().filter_map(from_json).collect()).unwrap_or_default()
}

fn write_custom(list: &[Pal]) { save_setting("palette_custom", list.iter().map(to_json).collect()); }

/// Inserts or replaces `p` by id; false when that would make more than three.
fn save_custom(p: Pal) -> bool {
    let mut list = custom();
    match list.iter().position(|x| x.id == p.id) {
        Some(i) => list[i] = p,
        None if list.len() >= MAX_CUSTOM => return false,
        None => list.push(p),
    }
    write_custom(&list);
    true
}

fn auto() -> bool { load_settings()["palette_auto"].as_bool().unwrap_or(false) }

/// The dark built-in Match system uses: OLED Black or Classic Dark.
fn auto_dark() -> &'static str { if load_settings()["palette_auto_dark"] == DARK { DARK } else { OLED } }

fn active_id(light: bool) -> String {
    if auto() { return if light { WHITE } else { auto_dark() }.into(); }
    load_settings()["palette_active"].as_str().unwrap_or(DEFAULT).into()
}

/// The active preset; a deleted custom one falls back to OLED Black.
fn active(light: bool, tr: &[String]) -> Pal {
    let id = active_id(light);
    if BUILT_IN.contains(&id.as_str()) { return built_in(&id, tr); }
    custom().into_iter().find(|p| p.id == id).unwrap_or_else(|| built_in(OLED, tr))
}

/// A pick. Under Match system a dark built-in becomes its dark half; any other pick turns Match system off.
fn set_active(id: &str) {
    if auto() && (id == OLED || id == DARK) {
        save_setting("palette_auto_dark", id.into());
    } else {
        save_setting("palette_auto", false.into());
        save_setting("palette_active", id.into());
    }
}

/// On keeps the current dark built-in as the dark half; off keeps what shows now.
fn set_auto(on: bool, light: bool) {
    let current = active_id(light);
    if on && current == DARK { save_setting("palette_auto_dark", DARK.into()); }
    if !on { save_setting("palette_active", current.into()); }
    save_setting("palette_auto", on.into());
}

/// A built-in's accent; its own style value removes the override.
fn set_accent(id: &str, c: Color, tr: &[String]) {
    let mut all = load_settings()["palette_accent"].clone();
    if !all.is_object() { all = json!({}); }
    all.as_object_mut().unwrap().remove(id);
    save_setting("palette_accent", all.clone());
    if built_in(id, tr).accent != c {
        all[id] = hex(c).into();
        save_setting("palette_accent", all);
    }
}

fn recent() -> Vec<Color> {
    load_settings()["recent_colors"].as_array()
        .map(|a| a.iter().filter_map(|v| v.as_str().and_then(parse_hex)).collect()).unwrap_or_default()
}

/// Most recent first, no duplicates; a repeat commit to `target` replaces its own entry, so one slider drag
/// after another leaves one colour.
fn remember(target: &str, c: Color) {
    let s = load_settings();
    let mut list = recent();
    if s["recent_colors_target"] == target && !list.is_empty() { list.remove(0); }
    list.retain(|x| *x != c);
    list.insert(0, c);
    list.truncate(MAX_RECENT);
    save_setting("recent_colors", list.into_iter().map(hex).collect());
    save_setting("recent_colors_target", target.into());
}

/// The contrast warning of each token (WCAG 2): text below 4.5:1 on card or background, accent below 3:1 on
/// background. It names the worse surface. "" = none.
fn warnings(p: &Pal, tr: &[String]) -> Vec<SharedString> {
    (0..6).map(|i| {
        let (ratio, surface, min) = match i {
            3 | 4 => {
                let c = tokens(p)[i];
                let (on_card, on_bg) = (contrast(c, p.card), contrast(c, p.bg));
                if on_card <= on_bg { (on_card, "token_card", 4.5) } else { (on_bg, "token_background", 4.5) }
            }
            2 => (contrast(p.accent, p.bg), "token_background", 3.0),
            _ => return SharedString::new(),
        };
        if ratio >= min { return SharedString::new(); }
        t(tr, "contrast_warning").replace("%1$s", &format!("{ratio:.1}")).replace("%2$s", t(tr, surface)).into()
    }).collect()
}

fn model<T: Clone + 'static>(v: Vec<T>) -> ModelRc<T> { ModelRc::new(VecModel::from(v)) }

/// Puts the active preset on every window and fills the Theme page and the preset editor.
pub fn apply(a: &App) {
    let th = a.main.global::<Theme>();
    let light = th.get_system_light();
    let tr = &a.tr;
    let p = active(light, tr);
    show(a, &p);

    let list = custom();
    th.set_builtins(model(BUILT_IN.iter().map(|id| built_in(id, tr)).collect()));
    th.set_active(p.clone());
    th.set_active_builtin(BUILT_IN.contains(&p.id.as_str()));
    th.set_auto(auto());
    th.set_auto_sub(t(tr, "theme_auto_sub").replace("%1$s", t(tr, name_key(WHITE)))
        .replace("%2$s", t(tr, name_key(auto_dark()))).into());
    th.set_custom_title(t(tr, "theme_custom").replace("%1$d", &list.len().to_string())
        .replace("%2$d", &MAX_CUSTOM.to_string()).into());
    let left = MAX_CUSTOM - list.len();
    th.set_new_sub(match left {
        0 => t(tr, "theme_new_full").into(),
        1 => t(tr, "theme_new_sub_one").into(),
        n => t(tr, "theme_new_sub").replace("%1$d", &n.to_string()).into(),
    });
    th.set_full(left == 0);
    th.set_swatches(model(["swatch_red", "swatch_orange", "swatch_green", "swatch_blue", "swatch_purple"]
        .iter().map(|n| xml_color(COLORS, 0, n)).collect()));
    th.set_recent(model(recent()));
    th.set_token_names(model(["token_background", "token_card", "token_accent", "token_text", "token_text_secondary",
        "token_outline"].iter().map(|k| SharedString::from(t(tr, k))).collect()));
    // The preset being edited, as saved.
    if let Some(e) = list.iter().find(|x| x.id == th.get_edit().id) {
        th.set_edit(e.clone());
        th.set_warnings(model(warnings(e, tr)));
        th.set_delete_title(t(tr, "preset_delete_title").replace("%1$s", &e.name).into());
        th.set_delete_body(if e.id == p.id { t(tr, "preset_delete_active").into() } else { SharedString::new() });
    }
    th.set_can_duplicate(left > 0);
    th.set_custom(model(list));
}

thread_local! {
    static FADE: slint::Timer = slint::Timer::default();
    /// False until the first theme is on screen: the launch shows it at once.
    static SHOWN: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}

/// The colours [paint] sets, in its order.
fn colors(p: &Pal) -> [Color; 8] { [p.bg, p.card, p.accent, p.text, p.text2, p.outline, on_accent(p), track(p)] }

fn paint(a: &App, c: &[Color; 8], light: bool) {
    let set = |g: Palette| {
        g.set_bg(c[0]);
        g.set_card(c[1]);
        g.set_accent(c[2]);
        g.set_text(c[3]);
        g.set_text2(c[4]);
        g.set_outline(c[5]);
        g.set_on_accent(c[6]);
        g.set_track(c[7]);
        g.set_light(light);
    };
    set(a.main.global::<Palette>());
    #[cfg(windows)]
    set(a.panel.global::<Palette>());
}

/// The phone's theme crossfade: every colour steps from the shown theme to [p] in 240 ms. The Dot matrix style
/// bakes colours into its images, and the launch has nothing to fade from: both switch at once.
fn show(a: &App, p: &Pal) {
    let to = colors(p);
    let light = is_light(p);
    let g = a.main.global::<Palette>();
    let from = [g.get_bg(), g.get_card(), g.get_accent(), g.get_text(), g.get_text2(), g.get_outline(), g.get_on_accent(), g.get_track()];
    FADE.with(|t| t.stop());
    if crate::STYLE.get().0 || !SHOWN.replace(true) || from == to { paint(a, &to, light); return; }
    let start = Instant::now();
    FADE.with(|t| t.start(slint::TimerMode::Repeated, Duration::from_millis(16), move || with_app(|a| {
        let k = (start.elapsed().as_secs_f32() / 0.24).min(1.0);
        let e = 1.0 - (1.0 - k) * (1.0 - k);
        let now: [Color; 8] = std::array::from_fn(|i| blend(from[i], to[i], e));
        // The light flag (a few borders) switches at the end, with the last step.
        paint(a, &now, if k < 1.0 { a.main.global::<Palette>().get_light() } else { light });
        if k >= 1.0 { FADE.with(|t| t.stop()); }
    })));
}

/// [apply], then every drawing that bakes a colour in (dot images, the EQ curve, the hearing chart).
fn refresh(a: &mut App) {
    apply(a);
    a.set_style(crate::STYLE.get().0, crate::STYLE.get().1);
}

fn light() -> bool {
    let mut l = false;
    with_app(|a| l = a.main.global::<Theme>().get_system_light());
    l
}

pub fn setup(main: &crate::MainWindow) {
    let th = main.global::<Theme>();
    th.on_hex(|c| hex(c).into());
    th.on_parse_hex(|s| parse_hex(&s).unwrap_or(Color::from_argb_u8(0, 0, 0, 0)));
    th.on_track(|p| track(&p));
    // A swatch dot outlined only where it would vanish into the screen.
    th.on_edge(|c, bg, ring| if contrast(c, bg) < 1.5 { ring } else { c });
    th.on_with_token(|p, i, c| with_token(&p, i as usize, c));
    th.on_system_changed(|| with_app(|a| if auto() { refresh(a) }));
    th.on_pick(|id| {
        let l = light();
        if id.as_str() == active_id(l) { return; }
        set_active(&id);
        with_app(refresh);
    });
    th.on_set_auto(|on| { set_auto(on, light()); with_app(refresh); });
    th.on_set_accent(|c| with_app(|a| {
        let p = active(a.main.global::<Theme>().get_system_light(), &a.tr);
        if BUILT_IN.contains(&p.id.as_str()) { set_accent(&p.id, c, &a.tr); refresh(a); }
    }));
    th.on_remember(|target, c| remember(&target, c));
    th.on_new_preset(|| {
        let mut ok = false;
        with_app(|a| {
            let used: Vec<String> = custom().into_iter().map(|p| p.name.to_string()).collect();
            let name = (1..=9).map(|n| t(&a.tr, "theme_new_name").replace("%1$d", &n.to_string()))
                .find(|n| !used.contains(n)).unwrap_or_default();
            let mut p = active(a.main.global::<Theme>().get_system_light(), &a.tr);
            p.id = new_id().into();
            p.name = name.into();
            ok = save_custom(p.clone());
            if ok { a.main.global::<Theme>().set_edit(p); refresh(a); }
        });
        ok
    });
    th.on_open(|id| with_app(|a| {
        if let Some(p) = custom().into_iter().find(|p| p.id == id) { a.main.global::<Theme>().set_edit(p); apply(a); }
    }));
    th.on_set_token(|i, c| with_app(|a| {
        let p = with_token(&a.main.global::<Theme>().get_edit(), i as usize, c);
        if save_custom(p) { refresh(a); }
    }));
    th.on_rename(|name| with_app(|a| {
        let th = a.main.global::<Theme>();
        let name: String = name.trim().chars().take(MAX_NAME).collect();
        let mut p = th.get_edit();
        if !name.is_empty() && name != p.name.as_str() { p.name = name.into(); save_custom(p); }
        apply(a);
    }));
    th.on_duplicate(|| with_app(|a| {
        let th = a.main.global::<Theme>();
        let mut p = th.get_edit();
        p.name = t(&a.tr, "theme_copy_name").replace("%1$s", &p.name).chars().take(MAX_NAME).collect::<String>().into();
        p.id = new_id().into();
        if save_custom(p.clone()) { th.set_edit(p); apply(a); }
    }));
    th.on_delete(|| with_app(|a| {
        let id = a.main.global::<Theme>().get_edit().id;
        write_custom(&custom().into_iter().filter(|p| p.id != id).collect::<Vec<_>>());
        if active_id(a.main.global::<Theme>().get_system_light()) == id.as_str() { set_active(OLED); }
        refresh(a);
    }));
}

/// "c" and the time in ms, as the phone names its presets.
fn new_id() -> String {
    format!("c{}", std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_millis())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn built_ins_and_colours() {
        let tr = crate::translations("");
        let dark = built_in(DARK, &tr);
        assert_eq!(hex(dark.bg), "#121214");
        assert_eq!(hex(built_in(WHITE, &tr).card), "#FFFFFF");
        assert!(is_light(&built_in(WHITE, &tr)) && !is_light(&dark));
        assert_eq!(parse_hex("d71920"), Some(Color::from_rgb_u8(0xD7, 0x19, 0x20)));
        assert_eq!(parse_hex("#12345"), None);
        assert!((contrast(Color::from_rgb_u8(0, 0, 0), Color::from_rgb_u8(255, 255, 255)) - 21.0).abs() < 0.01);
        // Pure red takes the white label, not black.
        let red = Pal { accent: Color::from_rgb_u8(255, 0, 0), ..dark.clone() };
        assert_eq!(on_accent(&red), dark.text);
        let p = from_json(&to_json(&dark)).unwrap();
        assert_eq!(tokens(&p), tokens(&dark));
        assert_eq!(with_token(&dark, 5, Color::from_rgb_u8(1, 2, 3)).outline, Color::from_rgb_u8(1, 2, 3));
        assert_eq!(warnings(&with_token(&dark, 3, dark.card), &tr)[3].is_empty(), false);
        assert!(warnings(&dark, &tr).iter().all(|w| w.is_empty()));
    }
}
