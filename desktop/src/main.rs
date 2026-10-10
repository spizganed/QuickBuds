//! QuickBuds for the desktop: a window, a tray icon (with a quick panel on Windows), one link thread.
#![windows_subsystem = "windows"]

mod bt;
mod controls;
mod devtools;
mod dots;
mod dual;
mod earbuds;
mod eq;
mod hearing;
mod models;
mod protocol;
mod report;
mod session;
mod theme;
mod update;

mod icons { include!(concat!(env!("OUT_DIR"), "/icons.rs")); }
mod strings { include!(concat!(env!("OUT_DIR"), "/strings.rs")); }

slint::include_modules!();

use protocol::{AncModes, LEVELS};
use session::{Cmd, Snapshot, Status};
use slint::winit_030::{winit, WinitWindowAccessor};
#[cfg(windows)]
use slint::winit_030::EventResult;
use slint::{ComponentHandle, Image, ModelRc, VecModel};
use std::cell::RefCell;
use std::rc::Rc;
use std::sync::mpsc::Sender;
use tray_icon::TrayIconBuilder;
#[cfg(windows)]
use tray_icon::{MouseButton, MouseButtonState, TrayIcon, TrayIconEvent};

/// Windows: the tray icon itself (left click opens the window, right click the quick panel, battery in the
/// tooltip). Linux: a tray icon can only open a menu (AppIndicator), so it is a battery line, Open and Quit,
/// run on its own GTK thread; this sends it the battery line.
#[cfg(windows)]
type Tray = TrayIcon;
#[cfg(not(windows))]
type Tray = std::sync::mpsc::Sender<String>;

struct App {
    main: MainWindow,
    #[cfg(windows)]
    panel: QuickPanel,
    tray: Tray,
    tx: Sender<Cmd>,
    /// Modes of the last buds seen: the controls stay in place (dimmed) while disconnected.
    modes: AncModes,
    last_level: Option<String>,
    tr: Vec<String>,
    /// The last state from the session.
    snap: Snapshot,
    /// The band editor's gains, updated in place while dragging.
    gains: Rc<VecModel<i32>>,
    /// EQ creates and deletes the buds have not confirmed yet.
    eq_pending: eq::Pending,
    /// The preset (id, name) the name field was last filled from.
    edit_key: Option<(u8, String)>,
    /// The band editor's plot size in pixels.
    plot: (f32, f32),
    /// Packet log lines already in the Dev tools list.
    log_shown: usize,
    /// What the last connected buds have; kept while disconnected, like [App::modes].
    has: Has,
}

thread_local! { static APP: RefCell<Option<App>> = const { RefCell::new(None) }; }

fn with_app(f: impl FnOnce(&mut App)) { APP.with(|a| a.borrow_mut().as_mut().map(f)); }

thread_local! {
    /// The Dot matrix style is on, and the main window's scale factor: icons are drawn for both.
    static STYLE: std::cell::Cell<(bool, f32)> = const { std::cell::Cell::new((false, 1.0)) };
}

/// An icon as the style draws it: the vector itself, or its dots (24 logical px tall).
fn svg(s: &str) -> Image { svg_at(s, 24.0) }

/// [svg] for an icon shown `size` logical px tall: dots are drawn at that size, a scaled dot image blurs.
fn svg_at(s: &str, size: f32) -> Image {
    let (dots_on, scale) = STYLE.get();
    if dots_on { dots::icon(s, size, scale) } else { raw_svg(s) }
}

fn raw_svg(s: &str) -> Image { Image::load_from_svg_data(s.as_bytes()).expect("icon") }

/// The local time now: (year, month, day, hour, minute, second, millisecond).
#[cfg(not(windows))]
fn local_now() -> [u32; 7] {
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default();
    // SAFETY: localtime_r fills the zeroed tm from a valid time_t.
    let tm = unsafe {
        let t = now.as_secs() as libc::time_t;
        let mut tm: libc::tm = std::mem::zeroed();
        libc::localtime_r(&t, &mut tm);
        tm
    };
    [tm.tm_year as u32 + 1900, tm.tm_mon as u32 + 1, tm.tm_mday as u32, tm.tm_hour as u32, tm.tm_min as u32, tm.tm_sec as u32, now.subsec_millis()]
}

#[cfg(windows)]
fn local_now() -> [u32; 7] {
    // SAFETY: GetLocalTime fills the zeroed SYSTEMTIME.
    let st = unsafe {
        let mut st = std::mem::zeroed();
        windows_sys::Win32::System::SystemInformation::GetLocalTime(&mut st);
        st
    };
    [st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, st.wSecond, st.wMilliseconds].map(u32::from)
}

/// `settings.json` in the user's config folder (`%APPDATA%\QuickBuds`, `~/.config/quickbuds`).
fn settings_path() -> Option<std::path::PathBuf> {
    #[cfg(windows)]
    let dir = std::env::var_os("APPDATA").map(|d| std::path::PathBuf::from(d).join("QuickBuds"));
    #[cfg(not(windows))]
    let dir = std::env::var_os("XDG_CONFIG_HOME").map(std::path::PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| std::path::PathBuf::from(h).join(".config")))
        .map(|d| d.join("quickbuds"));
    dir.map(|d| d.join("settings.json"))
}

fn load_settings() -> serde_json::Value {
    settings_path().and_then(|p| std::fs::read_to_string(p).ok())
        .and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_else(|| serde_json::json!({}))
}

fn save_setting(key: &str, value: serde_json::Value) {
    let Some(path) = settings_path() else { return };
    let mut all = load_settings();
    all[key] = value;
    if let Some(dir) = path.parent() { let _ = std::fs::create_dir_all(dir); }
    let _ = std::fs::write(path, serde_json::to_string_pretty(&all).unwrap_or_default());
}

/// The Dot matrix renderers for one window's `Dots` global.
fn setup_dots(d: &Dots) {
    let pitch = || dots::pitch_px(STYLE.get().1, dots::PITCH);
    d.on_box_px(move |w, h, fill, stroke, r| dots::dot_box(w, h, fill, stroke, r, pitch()));
    d.on_ring_px(|size, level, slot, tint, accent, track| dots::ring(size, level, slot, tint, accent, track));
    d.on_knob_px(move |ring, fill| dots::knob(ring, fill, pitch()));
    d.on_disc_px(move |n, c, edge| dots::disc(n.max(1) as u32, c, edge, pitch()));
    d.on_slider_px(move |w, ch, h, s, v| dots::slider(w, ch, h, s, v, pitch()));
    d.on_set_on(|on| {
        save_setting("dot_matrix", on.into());
        with_app(|a| a.set_style(on, STYLE.get().1));
    });
}

/// What the last connected buds have, as the phone gates its rows: game mode, equalizer, controls,
/// hearing profile, dual connection. Nothing read yet = all.
#[derive(Clone, Copy, PartialEq)]
struct Has { game: bool, eq: bool, controls: bool, hearing: bool, dual: bool }

impl Has {
    const ALL: Has = Has { game: true, eq: true, controls: true, hearing: true, dual: true };
    fn of(s: &Snapshot) -> Has {
        Has {
            game: protocol::has_feature(&s.features, s.caps.game_mode_id()),
            eq: protocol::eq_has(s.model, &s.caps),
            controls: controls::available(s),
            hearing: protocol::has_feature(&s.features, protocol::FEATURE_HEARING),
            dual: protocol::has_feature(&s.features, protocol::FEATURE_DUAL),
        }
    }
}

/// The sidebar's sections, titled as their pages; a page the buds have no feature for is hidden.
fn nav(h: Has, tr: &[String]) -> ModelRc<NavEntry> {
    let nav: Vec<NavEntry> = [
        (icons::LAYOUT, "desktop_nav_overview", true),
        (icons::EQUALIZER, "eq_title", h.eq),
        (icons::GESTURE, "gesture_title", h.controls),
        (icons::HEARING, "row_golden_title", h.hearing),
        (icons::DEVICES, "dual_title", h.dual),
        (icons::EARBUD, "earbuds_title", true),
        (icons::SETTINGS_COG, "desktop_nav_app_settings", true),
        (icons::DEV_TOOLS, "action_dev_tools", true),
    ].into_iter().map(|(icon, key, ready)| NavEntry { icon: svg_at(icon, 20.0), name: t(tr, key).into(), ready }).collect();
    ModelRc::new(VecModel::from(nav))
}

/// Settings › Language, as on the phone: (tag in `strings::LOCALES`, native name); "" is the system default.
const LANGUAGES: &[(&str, &str)] = &[
    ("", ""), ("en", "English"), ("zh-CN", "简体中文"), ("zh-TW", "繁體中文"), ("ja", "日本語"), ("ko", "한국어"),
    ("in", "Bahasa Indonesia"), ("ms", "Bahasa Melayu"), ("cs", "Čeština"), ("de", "Deutsch"), ("es", "Español"),
    ("fil", "Filipino"), ("fr", "Français"), ("it", "Italiano"), ("hu", "Magyar"), ("nl", "Nederlands"),
    ("pl", "Polski"), ("pt", "Português"), ("ro", "Română"), ("sv", "Svenska"), ("vi", "Tiếng Việt"),
    ("tr", "Türkçe"), ("el", "Ελληνικά"), ("ru", "Русский"), ("uk", "Українська"), ("hi", "हिन्दी"),
    ("bn", "বাংলা"), ("th", "ไทย"),
];

/// The locale table for a language tag ("de-AT" de, "zh-Hant-HK" zh-TW, "id" in); English without one.
fn locale_for(tag: &str) -> &'static str {
    let tag = tag.replace('_', "-");
    let parts: Vec<&str> = tag.split(['-', '.']).collect();
    let lang = match parts[0].to_lowercase().as_str() {
        "zh" if parts.iter().any(|p| ["Hant", "TW", "HK", "MO"].contains(p)) => "zh-TW".to_string(),
        "zh" => "zh-CN".into(),
        "id" => "in".into(),
        "tl" => "fil".into(),
        l => l.into(),
    };
    strings::LOCALES.iter().find(|l| l.0 == lang).map_or("", |l| l.0)
}

/// The saved language (`settings.json` "language"), else the system's.
fn chosen_locale() -> &'static str {
    let saved = load_settings()["language"].as_str().unwrap_or("").to_string();
    locale_for(&if saved.is_empty() { sys_locale::get_locale().unwrap_or_default() } else { saved })
}

/// The strings of one locale; a key it lacks falls back to English.
fn translations(locale: &str) -> Vec<String> {
    let table = |tag: &str| strings::LOCALES.iter().find(|l| l.0 == tag).map(|l| l.1);
    let base = table("").unwrap();
    let chosen = table(locale).unwrap_or(base);
    chosen.iter().zip(base).map(|(c, b)| c.or(*b).unwrap_or("").to_string()).collect()
}

/// Settings › Language's list and current pick, and the report's categories, in the current strings.
fn language_ui(main: &MainWindow, tr: &[String]) {
    let saved = load_settings()["language"].as_str().unwrap_or("").to_string();
    let current = LANGUAGES.iter().position(|l| l.0 == saved).unwrap_or(0);
    let label = |i: usize| if i == 0 { t(tr, "language_system") } else { LANGUAGES[i].1 };
    let u = main.global::<Update>();
    u.set_languages(ModelRc::new(VecModel::from((0..LANGUAGES.len())
        .map(|i| Choice { value: i as i32, label: label(i).into() }).collect::<Vec<_>>())));
    u.set_language(current as i32);
    u.set_language_name(label(current).into());
    main.global::<Report>().set_categories(ModelRc::new(VecModel::from(
        report::CATEGORY_KEYS.iter().map(|k| t(tr, k).into()).collect::<Vec<slint::SharedString>>())));
}

/// The default size shows every page without a scroll; on a smaller screen the window shrinks to fit it.
fn fit_screen(win: &slint::Window) {
    let fit = win.with_winit_window(|w| {
        let m = w.current_monitor().or_else(|| w.primary_monitor())?;
        let screen = m.size().to_logical::<f32>(m.scale_factor());
        let size = w.inner_size().to_logical::<f32>(w.scale_factor());
        // Room for the panel and the window frame.
        let fit = slint::LogicalSize::new((screen.width * 0.95).min(size.width), (screen.height * 0.9).min(size.height));
        (fit.width < size.width || fit.height < size.height).then_some(fit)
    });
    if let Some(Some(fit)) = fit { win.set_size(fit); }
}

/// Puts `rows` into a repeater's model in place: a row that stays keeps its element, so its switch slides
/// (a new model rebuilds every row, and nothing animates). A new row count refills the same model.
fn update_rows<T: Clone + PartialEq + 'static>(current: ModelRc<T>, rows: Vec<T>, set: impl FnOnce(ModelRc<T>)) {
    use slint::Model;
    let Some(m) = current.as_any().downcast_ref::<VecModel<T>>() else { return set(ModelRc::new(VecModel::from(rows))) };
    if m.row_count() != rows.len() { return m.set_vec(rows); }
    for (i, r) in rows.into_iter().enumerate() {
        if m.row_data(i).as_ref() != Some(&r) { m.set_row_data(i, r); }
    }
}

fn t<'a>(tr: &'a [String], key: &str) -> &'a str {
    &tr[strings::KEYS.iter().position(|k| *k == key).expect(key)]
}

fn set_icons(b: &Buds) {
    b.set_icon_left(svg(icons::BUD_LEFT));
    b.set_icon_update(svg(icons::UPDATE));
    b.set_icon_battery(svg(icons::BATTERY));
    b.set_icon_right(svg(icons::BUD_RIGHT));
    b.set_icon_case(svg(icons::CASE));
    b.set_icon_off(svg(icons::MODE_OFF));
    b.set_icon_anc(svg(icons::MODE_ANC_MEDIUM));
    b.set_icon_adaptive(svg(icons::MODE_ADAPTIVE));
    b.set_icon_transparency(svg(icons::MODE_TRANSPARENCY));
    b.set_icon_low_latency(svg(icons::LOW_LATENCY));
    b.set_icon_app(raw_svg(icons::LAUNCHER));
    b.set_icon_bass(svg(icons::EQUALIZER));
    b.set_icon_chevron(svg_at(icons::CHEVRON_RIGHT, 20.0));
    b.set_icon_plus(svg_at(icons::PLUS, 18.0));
    b.set_icon_info(svg(icons::INFO));
    b.set_icon_find(svg(icons::FIND_BUDS));
    b.set_icon_earbud(svg(icons::EARBUD));
    b.set_icon_volume(svg_at(icons::VOLUME, 22.0));
    b.set_icon_volume_off(svg_at(icons::VOLUME_OFF, 22.0));
    b.set_icon_devices(svg(icons::DEVICES));
    b.set_icon_hearing(svg(icons::HEARING));
    b.set_icon_language(svg(icons::LANGUAGE));
    b.set_icon_palette(svg(icons::PALETTE));
    b.set_icon_pencil(svg_at(icons::PENCIL, 22.0));
    b.set_icon_copy(svg_at(icons::COPY, 22.0));
    b.set_icon_delete(svg_at(icons::DELETE, 22.0));
    b.set_icon_warning(svg_at(icons::WARNING, 14.0));
    b.set_icon_chevron_down(svg_at(icons::CHEVRON_DOWN, 20.0));
    b.set_icon_hires(svg_at(icons::HIRES, 20.0));
}

fn setup_ui(b: &Buds, tr: &Tr, s: &[String]) {
    set_icons(b);
    set_texts(tr, s);
    setup_callbacks(b);
}

fn set_texts(tr: &Tr, s: &[String]) {
    tr.set_codec_dialog_title(t(s, "codec_dialog_title").into());
    tr.set_codec_dialog_accept(t(s, "codec_dialog_accept").into());
    tr.set_codec_msg_reconnect(t(s, "codec_msg_reconnect").into());
    tr.set_language_title(t(s, "settings_language_title").into());
    tr.set_language_sub(t(s, "settings_language_sub").into());
    tr.set_app_settings_title(t(s, "desktop_nav_app_settings").into());
    tr.set_dev_tools_title(t(s, "action_dev_tools").into());
    tr.set_connected(t(s, "conn_on").into());
    tr.set_connect(t(s, "conn_action_connect").into());
    tr.set_disconnect(t(s, "conn_action_disconnect").into());
    tr.set_connecting(t(s, "conn_connecting").into());
    tr.set_not_connected(t(s, "dual_not_connected").into());
    tr.set_left(t(s, "status_left").into());
    tr.set_right(t(s, "status_right").into());
    tr.set_case(t(s, "status_case").into());
    tr.set_noise_control(t(s, "anc_section").into());
    tr.set_off(t(s, "anc_seg_off").into());
    tr.set_anc(t(s, "anc_seg_anc").into());
    tr.set_anc_need_ear(t(s, "anc_need_ear").into());
    tr.set_adaptive(t(s, "anc_seg_adapt").into());
    tr.set_transparency(t(s, "anc_seg_trans").into());
    tr.set_low_latency(t(s, "widget_low_latency").into());
    tr.set_in_ear(t(s, "status_in_ear").into());
    tr.set_in_case(t(s, "status_in_case").into());
    tr.set_out_of_ear(t(s, "status_out").into());
    tr.set_game_title(t(s, "row_game_title").into());
    tr.set_game_sub(t(s, "row_game_sub").into());
    tr.set_eq_row_title(t(s, "row_eq_title").into());
    tr.set_eq_row_sub(t(s, "row_eq_sub").into());
    tr.set_dual_row_sub(t(s, "row_dual_sub").into());
    tr.set_earbuds_row_sub(t(s, "row_earbuds_sub").into());
    tr.set_eq_title(t(s, "eq_title").into());
    tr.set_eq_not_connected(t(s, "eq_not_connected").into());
    tr.set_eq_recommended(t(s, "eq_recommended").into());
    tr.set_eq_basswave(t(s, "eq_basswave").into());
    tr.set_eq_basswave_sub(t(s, "eq_basswave_sub").into());
    tr.set_eq_custom(t(s, "eq_custom").into());
    tr.set_eq_delete(t(s, "eq_delete").into());
    tr.set_eq_add(t(s, "eq_add").into());
    tr.set_eq_save(t(s, "eq_save").into());
    tr.set_style_title(t(s, "widget_style_title").into());
    tr.set_theme_title(t(s, "theme_title").into());
    tr.set_theme_builtin(t(s, "theme_builtin").into());
    tr.set_theme_auto(t(s, "theme_auto").into());
    tr.set_theme_accent(t(s, "theme_accent").into());
    tr.set_theme_new(t(s, "theme_new").into());
    tr.set_theme_footer(t(s, "theme_footer").into());
    tr.set_theme_edit(t(s, "theme_edit").into());
    tr.set_preset_title(t(s, "preset_title").into());
    tr.set_preset_name(t(s, "preset_name").into());
    tr.set_preset_preview(t(s, "preset_preview").into());
    tr.set_preset_colors(t(s, "preset_colors").into());
    tr.set_preset_duplicate(t(s, "preset_duplicate").into());
    tr.set_preset_delete(t(s, "preset_delete").into());
    tr.set_preset_hue(t(s, "preset_hue").into());
    tr.set_preset_saturation(t(s, "preset_saturation").into());
    tr.set_preset_brightness(t(s, "preset_brightness").into());
    tr.set_preset_recent(t(s, "preset_recent").into());
    tr.set_preset_hex(t(s, "preset_hex").into());
    tr.set_hires_title(t(s, "row_hires_title").into());
    tr.set_hires_sub(t(s, "row_hires_sub").into());
    tr.set_update_title(t(s, "update_title").into());
    tr.set_update_installed(t(s, "update_installed").into());
    tr.set_update_check(t(s, "update_check").into());
    tr.set_update_install(t(s, "update_install").into());
    tr.set_update_auto_title(t(s, "update_auto_title").into());
    tr.set_update_auto_sub(t(s, "update_auto_sub").into());
    tr.set_clear_battery_title(t(s, "settings_clear_battery_title").into());
    tr.set_clear_battery_sub(t(s, "settings_clear_battery_sub").into());
    tr.set_style_classic(t(s, "widget_style_classic").into());
    tr.set_style_dots(t(s, "widget_style_nothing").into());
    tr.set_earbuds_title(t(s, "earbuds_title").into());
    tr.set_problem_title(t(s, "problem_title").into());
    tr.set_problem_sub(t(s, "problem_sub").into());
    tr.set_problem_intro(t(s, "problem_intro").into());
    tr.set_problem_model(t(s, "problem_model").into());
    tr.set_problem_category(t(s, "problem_category").into());
    tr.set_problem_description(t(s, "problem_description").into());
    tr.set_problem_description_hint(t(s, "problem_description_hint").into());
    tr.set_problem_log_title(t(s, "problem_log_title").into());
    tr.set_problem_preview(t(s, "problem_preview").into());
    tr.set_problem_send(t(s, "problem_send").into());
    tr.set_problem_sending(t(s, "problem_sending").into());
    tr.set_section_features(t(s, "earbuds_section_features").into());
    tr.set_section_about(t(s, "earbuds_section_about").into());
    tr.set_firmware_title(t(s, "row_firmware_title").into());
    tr.set_firmware_dialog_title(t(s, "firmware_dialog_title").into());
    tr.set_firmware_dialog_body(t(s, "firmware_dialog_body").into());
    tr.set_close(t(s, "dialog_close").into());
    tr.set_cancel(t(s, "dialog_cancel").into());
    tr.set_ok(t(s, "dual_add_ok").into());
    tr.set_find_row_title(t(s, "row_find_title").into());
    tr.set_find_row_sub(t(s, "row_find_sub").into());
    tr.set_find_title(t(s, "find_title").into());
    tr.set_find_hint(t(s, "find_hint").into());
    tr.set_find_stop(t(s, "find_stop").into());
    tr.set_find_play(t(s, "find_play").into());
    tr.set_find_warn_title(t(s, "find_warn_title").into());
    tr.set_find_warn_msg(t(s, "find_warn_msg").into());
    tr.set_find_warn_play(t(s, "find_warn_play").into());
    tr.set_section_sounds(t(s, "earbuds_section_sounds").into());
    tr.set_fit_title(t(s, "fit_title").into());
    tr.set_fit_sub(t(s, "fit_sub").into());
    tr.set_fit_hint(t(s, "fit_hint").into());
    tr.set_fit_play(t(s, "fit_play").into());
    tr.set_fit_playing(t(s, "fit_playing").into());
    tr.set_fit_keep(t(s, "fit_keep").into());
    tr.set_fit_good(t(s, "fit_good").into());
    tr.set_fit_average(t(s, "fit_average").into());
    tr.set_fit_poor(t(s, "fit_poor").into());
    tr.set_fit_perfect(t(s, "fit_perfect").into());
    tr.set_fit_adjust(t(s, "fit_adjust").into());
    tr.set_fit_again(t(s, "fit_again").into());
    tr.set_fit_insert(t(s, "fit_insert").into());
    tr.set_fit_failed(t(s, "fit_failed").into());
    tr.set_bud_left(t(s, "gesture_bud_left").into());
    tr.set_bud_right(t(s, "gesture_bud_right").into());
    tr.set_done(t(s, "gesture_done").into());
    // "Adjust the position of the %1$s …", filled per side.
    let tips = |k| t(s, "fit_adjust_tips").replace("%1$s", t(s, k));
    tr.set_fit_tips_both(tips("fit_both").into());
    tr.set_game_sound_type_title(t(s, "game_sound_type_title").into());
    tr.set_head_motion_type_title(t(s, "head_motion_type_title").into());
    tr.set_fit_tips_left(tips("fit_left").into());
    tr.set_fit_tips_right(tips("fit_right").into());
    tr.set_pnc_title(t(s, "pnc_title").into());
    tr.set_pnc_sub(t(s, "pnc_sub").into());
    tr.set_pnc_wear(t(s, "pnc_wear").into());
    tr.set_pnc_stored_title(t(s, "pnc_stored_title").into());
    tr.set_pnc_stored_body(t(s, "pnc_stored_body").into());
    tr.set_pnc_use(t(s, "pnc_use").into());
    tr.set_pnc_test_again(t(s, "pnc_test_again").into());
    tr.set_pnc_test_title(t(s, "pnc_test_title").into());
    tr.set_pnc_test_body(t(s, "pnc_test_body").into());
    tr.set_pnc_start(t(s, "pnc_start").into());
    tr.set_pnc_testing(t(s, "pnc_testing").into());
    tr.set_pnc_done(t(s, "pnc_done").into());
    tr.set_pnc_failed(t(s, "pnc_failed").into());
    tr.set_pnc_retry(t(s, "pnc_retry").into());
    tr.set_pnc_fail_quiet(t(s, "pnc_fail_quiet").into());
    tr.set_pnc_fail_fit(t(s, "pnc_fail_fit").into());
    tr.set_pnc_fail_wind(t(s, "pnc_fail_wind").into());
    tr.set_pnc_fail_still(t(s, "pnc_fail_still").into());
    tr.set_pnc_fail_audio(t(s, "pnc_fail_audio").into());
    tr.set_model_title(t(s, "model_title").into());
    tr.set_model_auto(t(s, "model_auto").into());
    tr.set_alert_title(t(s, "row_alert_title").into());
    tr.set_tap_title(t(s, "tap_level_title").into());
    tr.set_tap_hint(t(s, "tap_level_hint").into());
    tr.set_tap_warning(t(s, "tap_level_warning").into());
    tr.set_gesture_title(t(s, "gesture_title").into());
    tr.set_gesture_not_in_call(t(s, "gesture_section_not_in_call").into());
    tr.set_gesture_on_call(t(s, "gesture_section_on_call").into());
    tr.set_gesture_note(t(s, "gesture_write_note").into());
    tr.set_hearing_title(t(s, "row_golden_title").into());
    tr.set_hearing_sub(t(s, "row_golden_sub").into());
    tr.set_hearing_profiles(t(s, "golden_profiles").into());
    tr.set_hearing_none(t(s, "golden_none").into());
    tr.set_hearing_test_row(t(s, "golden_test_row").into());
    tr.set_hearing_test_sub(t(s, "golden_test_sub").into());
    tr.set_hearing_left(t(s, "golden_left").into());
    tr.set_hearing_right(t(s, "golden_right").into());
    tr.set_hearing_boost(t(s, "golden_boost").into());
    tr.set_dual_title(t(s, "dual_title").into());
    tr.set_dual_switch_sub(t(s, "dual_switch_sub").into());
    tr.set_dual_section_devices(t(s, "dual_section_devices").into());
    tr.set_dual_section_all(t(s, "dual_section_all").into());
    tr.set_dual_add_title(t(s, "dual_add_title").into());
    tr.set_dual_add_message(t(s, "dual_add_message").into());
    tr.set_dual_preferred(t(s, "dual_preferred").into());
    tr.set_dual_preferred_sub(t(s, "dual_preferred_sub").into());
}

fn setup_callbacks(b: &Buds) {
    b.on_set_anc(|mode| with_app(|a| {
        let mode = if mode == "ANC" {
            // The ANC segment picks the last level used, else Medium, else the first the buds have.
            let levels = a.modes.levels();
            let Some(l) = a.last_level.clone().filter(|l| levels.contains(&l.as_str()))
                .or_else(|| levels.iter().find(|l| **l == "ANC-Medium").or(levels.first()).map(|l| l.to_string()))
            else { return };
            l
        } else {
            mode.to_string()
        };
        let _ = a.tx.send(Cmd::Anc(mode));
    }));
    b.on_set_low_latency(|on| with_app(|a| { let _ = a.tx.send(Cmd::LowLatency(on)); }));
    b.on_connect(|| with_app(|a| { let _ = a.tx.send(Cmd::Connect); }));
    b.on_disconnect(|| with_app(|a| { let _ = a.tx.send(Cmd::Disconnect); }));
    b.on_open_main(|| with_app(|a| {
        #[cfg(windows)]
        a.panel.hide().ok();
        show_main(&a.main);
    }));
    b.on_quit(|| { let _ = slint::quit_event_loop(); });
}

/// (mode id, icon, index into the level names)
fn level_info(id: &str) -> (&'static str, usize) {
    match id {
        "ANC-Light" => (icons::MODE_ANC_LOW, 0),
        "ANC-Deep" => (icons::MODE_ANC_HIGH, 2),
        "ANC-Smart" => (icons::MODE_ANC_SMART, 3),
        _ => (icons::MODE_ANC_MEDIUM, 1),
    }
}

impl App {
    /// Switches the style (or redraws it for a new scale factor) in the window and the quick panel.
    fn set_style(&mut self, on: bool, scale: f32) {
        STYLE.set((on, scale));
        #[cfg(windows)]
        let windows = [(self.main.global::<Dots>(), self.main.global::<Buds>()), (self.panel.global::<Dots>(), self.panel.global::<Buds>())];
        #[cfg(not(windows))]
        let windows = [(self.main.global::<Dots>(), self.main.global::<Buds>())];
        for (d, b) in windows {
            d.set_scale(scale);
            d.set_pitch(dots::pitch_px(scale, dots::PITCH));
            d.set_on(on);
            set_icons(&b);
        }
        self.main.set_nav(nav(self.has, &self.tr));
        self.apply(self.snap.clone());
        eq::redraw(self);
    }

    /// Settings › Language: saves the pick and redraws every string in place.
    fn set_language(&mut self, i: usize) {
        save_setting("language", LANGUAGES[i].0.into());
        self.tr = translations(chosen_locale());
        set_texts(&self.main.global::<Tr>(), &self.tr);
        #[cfg(windows)]
        set_texts(&self.panel.global::<Tr>(), &self.tr);
        language_ui(&self.main, &self.tr);
        self.set_style(STYLE.get().0, STYLE.get().1);
    }

    fn apply(&mut self, s: Snapshot) {
        self.snap = s.clone();
        eq::apply(self);
        earbuds::apply(self);
        models::apply(self);
        dual::apply(self);
        hearing::apply(self);
        controls::apply(self);
        if s.status == Status::On { self.modes = s.modes.clone(); }
        if s.status == Status::On && Has::of(&s) != self.has {
            self.has = Has::of(&s);
            self.main.set_nav(nav(self.has, &self.tr));
            let h = self.has;
            if !match self.main.get_page() { 1 => h.eq, 2 => h.controls, 3 => h.hearing, 4 => h.dual, _ => true } { self.main.set_page(0); }
        }
        earbuds::home(self);
        if let Some(a) = s.anc.as_deref().filter(|a| LEVELS.contains(a)) { self.last_level = Some(a.into()); }
        let level_names = ["anc_mode_low", "anc_mode_medium", "anc_mode_high", "anc_mode_smart"];
        let levels: Vec<Level> = self.modes.levels().into_iter().map(|id| {
            let (icon, i) = level_info(id);
            Level { id: id.into(), name: t(&self.tr, level_names[i]).into(), icon: svg(icon) }
        }).collect();
        let anc = s.anc.clone().unwrap_or_default();
        let is_level = LEVELS.contains(&anc.as_str());
        let (anc_icon, li) = level_info(if is_level { &anc } else { "" });
        // "ANC M": the level's first letter, as on the phone's segment and the widget's button.
        let mut anc_label = t(&self.tr, "anc_seg_anc").to_string();
        if is_level {
            if let Some(c) = t(&self.tr, level_names[li]).chars().next() { anc_label = format!("{anc_label} {}", c.to_uppercase()); }
            // Smart adds the level it chose: "ANC S·M".
            if let Some(c) = s.smart_level.as_deref().filter(|_| anc == "ANC-Smart").and_then(|l| t(&self.tr, level_names[level_info(l).1]).chars().next()) {
                anc_label = format!("{anc_label}·{}", c.to_uppercase());
            }
        }

        #[cfg(windows)]
        let globals = [self.main.global::<Buds>(), self.panel.global::<Buds>()];
        #[cfg(not(windows))]
        let globals = [self.main.global::<Buds>()];
        for b in globals {
            b.set_status(match s.status { Status::Off => 0, Status::Connecting => 1, Status::On => 2 });
            b.set_name(s.name.as_str().into());
            let bat = |i: usize| s.battery[i].map_or(-1, |x| x.0 as i32);
            let chg = |i: usize| s.battery[i].is_some_and(|x| x.1);
            b.set_left(bat(0));
            b.set_right(bat(1));
            b.set_case(bat(2));
            b.set_left_charging(chg(0));
            b.set_right_charging(chg(1));
            b.set_case_charging(chg(2));
            // §8: 3/7 in ear, 4 in case, 1/5 out
            let wear = |i: usize| match s.wear[i] { 3 | 7 => 2, 4 => 3, 1 | 5 => 1, _ => 0 };
            b.set_left_wear(wear(0));
            b.set_right_wear(wear(1));
            b.set_anc(anc.as_str().into());
            b.set_anc_is_level(is_level);
            b.set_anc_refused(s.anc_refused as i32);
            b.set_icon_anc(svg(anc_icon));
            b.set_anc_label(anc_label.as_str().into());
            b.set_has_off(self.modes.supports(protocol::OFF));
            b.set_has_adaptive(self.modes.supports(protocol::ADAPTIVE));
            b.set_has_transparency(self.modes.supports(protocol::TRANSPARENCY));
            b.set_levels(ModelRc::new(VecModel::from(levels.clone())));
            b.set_low_latency(s.low_latency.unwrap_or(false));
            b.set_has_game(self.has.game);
            b.set_has_eq(self.has.eq);
        }

        #[cfg(windows)]
        {
            let mut tip = String::from("QuickBuds");
            if s.status == Status::On {
                let parts: Vec<String> = [("status_left", 0), ("status_right", 1), ("status_case", 2)].iter()
                    .filter_map(|(k, i)| s.battery[*i].map(|b| format!("{} {}%", t(&self.tr, k), b.0)))
                    .collect();
                if !parts.is_empty() { tip = format!("{}\n{}", s.name, parts.join(" · ")); }
            }
            let _ = self.tray.set_tooltip(Some(tip));
        }
        // The menu's battery line is short: "L:10 C:40 R:50".
        #[cfg(not(windows))]
        let _ = self.tray.send(if s.status != Status::On { "QuickBuds".into() } else {
            let line: Vec<String> = [("L", 0), ("C", 2), ("R", 1)].iter()
                .filter_map(|(k, i)| s.battery[*i].map(|b| format!("{k}:{}", b.0))).collect();
            if line.is_empty() { s.name.clone() } else { line.join(" ") }
        });
    }
}

/// The frameless main window's own title bar and edges, through winit.
fn window_chrome(main: &MainWindow) {
    use winit::window::ResizeDirection as R;
    let w = main.as_weak();
    main.on_drag(move || { w.upgrade().map(|m| { m.window().with_winit_window(|w| w.drag_window()); release(&m); }); });
    let w = main.as_weak();
    main.on_minimize(move || { w.upgrade().map(|m| m.window().set_minimized(true)); });
    let w = main.as_weak();
    main.on_toggle_maximize(move || {
        if let Some(m) = w.upgrade() {
            let max = !m.window().is_maximized();
            m.window().set_maximized(max);
            m.set_max_state(max);
        }
    });
    let w = main.as_weak();
    main.on_close_window(move || { w.upgrade().map(|m| m.hide()); });
    let w = main.as_weak();
    main.on_resize(move |dir| {
        let dir = [R::North, R::South, R::West, R::East, R::NorthWest, R::NorthEast, R::SouthWest, R::SouthEast][dir as usize];
        w.upgrade().map(|m| { m.window().with_winit_window(|w| w.drag_resize_window(dir)); release(&m); });
    });
}

/// The compositor owns the pointer from here and Slint never sees the button go up: without an exit, the
/// pressed edge or title bar keeps every mouse event after the drag. The exit goes after the press is handled:
/// inside the handler, Slint puts the press state back over it.
fn release(m: &MainWindow) {
    let w = m.as_weak();
    slint::Timer::single_shot(std::time::Duration::ZERO, move || {
        w.upgrade().map(|m| m.window().dispatch_event(slint::platform::WindowEvent::PointerExited));
    });
}

fn show_main(w: &MainWindow) {
    w.show().ok();
    w.window().with_winit_window(|w| w.focus_window());
}

#[cfg(windows)]
fn on_tray(e: TrayIconEvent) {
    let TrayIconEvent::Click { button, button_state: MouseButtonState::Up, position, .. } = e else { return };
    with_app(|a| match button {
        MouseButton::Left => { a.panel.hide().ok(); show_main(&a.main); }
        MouseButton::Right => {
            // Above the cursor and left of it (taskbar at the bottom right); below it if that is off screen.
            let scale = a.panel.window().scale_factor();
            let (w, h) = ((300.0 * scale) as i32, (250.0 * scale) as i32);
            let (x, y) = (position.x as i32, position.y as i32);
            let px = if x - w >= 0 { x - w } else { x };
            let py = if y - h >= 0 { y - h } else { y };
            a.panel.window().set_position(slint::PhysicalPosition::new(px, py));
            a.panel.show().ok();
            a.panel.window().with_winit_window(|w| w.focus_window());
        }
        _ => {}
    });
}

fn tray_icon() -> tray_icon::Icon {
    let tree = resvg::usvg::Tree::from_str(icons::LAUNCHER, &Default::default()).expect("tray svg");
    let size = 32;
    let mut pm = resvg::tiny_skia::Pixmap::new(size, size).unwrap();
    let s = size as f32 / tree.size().width().max(tree.size().height());
    resvg::render(&tree, resvg::tiny_skia::Transform::from_scale(s, s), &mut pm.as_mut());
    tray_icon::Icon::from_rgba(pm.take_demultiplied(), size, size).expect("tray icon")
}

#[cfg(not(windows))]
fn linux_tray() -> std::sync::mpsc::Sender<String> {
    use tray_icon::menu::{Menu, MenuEvent, MenuItem};
    let (tx, rx) = std::sync::mpsc::channel::<String>();
    std::thread::spawn(move || {
        gtk::init().expect("gtk");
        let battery = MenuItem::new("QuickBuds", false, None);
        let open = MenuItem::new("Open QuickBuds", true, None);
        let quit = MenuItem::new("Quit", true, None);
        let menu = Menu::with_items(&[&battery, &open, &quit]).expect("tray menu");
        let _tray = TrayIconBuilder::new().with_icon(tray_icon()).with_menu(Box::new(menu)).build().expect("tray");
        let (open_id, quit_id) = (open.id().clone(), quit.id().clone());
        MenuEvent::set_event_handler(Some(move |e: MenuEvent| {
            if e.id == open_id {
                let _ = slint::invoke_from_event_loop(|| with_app(|a| show_main(&a.main)));
            } else if e.id == quit_id {
                let _ = slint::invoke_from_event_loop(|| { let _ = slint::quit_event_loop(); });
            }
        }));
        // ponytail: polls twice a second; battery changes slowly, an async channel is not worth it.
        gtk::glib::timeout_add_local(std::time::Duration::from_millis(500), move || {
            if let Some(text) = rx.try_iter().last() { battery.set_text(text); }
            gtk::glib::ControlFlow::Continue
        });
        gtk::main();
    });
    tx
}

fn main() {
    report::catch_panics();
    report::capture_stderr();
    // Software rendering: ~25 MB instead of ~130 MB with the GPU renderer, and fast enough for this UI.
    if std::env::var_os("SLINT_BACKEND").is_none() {
        slint::BackendSelector::new().backend_name("winit".into()).renderer_name("software".into())
            .select().expect("backend");
    }
    // Wayland app_id = the .desktop file name: Plasma takes the task switcher icon from that file.
    slint::set_xdg_app_id("quickbuds").ok();
    let main = MainWindow::new().expect("window");
    // Windows only: on Wayland even a never-shown window is a toplevel, and Plasma lists it in the taskbar.
    #[cfg(windows)]
    let panel = QuickPanel::new().expect("panel");
    let tr = translations(chosen_locale());
    setup_ui(&main.global::<Buds>(), &main.global::<Tr>(), &tr);
    #[cfg(windows)]
    setup_ui(&panel.global::<Buds>(), &panel.global::<Tr>(), &tr);
    setup_dots(&main.global::<Dots>());
    #[cfg(windows)]
    setup_dots(&panel.global::<Dots>());
    window_chrome(&main);
    // A new scale factor (another monitor) redraws the dots at its pitch.
    // The first event: the winit window exists from here on.
    let fitted = std::cell::Cell::new(false);
    main.window().on_winit_window_event(move |w, e| {
        if !fitted.replace(true) { fit_screen(w); }
        if let winit::event::WindowEvent::ScaleFactorChanged { scale_factor, .. } = e {
            let scale = *scale_factor as f32;
            let _ = slint::invoke_from_event_loop(move || with_app(|a| a.set_style(STYLE.get().0, scale)));
        }
        slint::winit_030::EventResult::Propagate
    });
    eq::setup(&main);
    earbuds::setup(&main);
    models::setup(&main);
    dual::setup(&main);
    hearing::setup(&main);
    controls::setup(&main);
    devtools::setup(&main);
    report::setup(&main);
    theme::setup(&main);
    language_ui(&main, &tr);
    main.global::<Update>().on_set_language(|i| with_app(|a| a.set_language(i as usize)));
    let gains = Rc::new(VecModel::default());
    main.global::<Eq>().set_gains(ModelRc::from(gains.clone()));

    // The quick panel closes when it loses focus, like the system's own tray flyouts.
    #[cfg(windows)]
    panel.window().on_winit_window_event(|w, e| {
        if let winit::event::WindowEvent::Focused(false) = e { w.hide().ok(); }
        EventResult::Propagate
    });

    #[cfg(windows)]
    let tray = TrayIconBuilder::new()
        .with_icon(tray_icon())
        .with_tooltip("QuickBuds")
        .build()
        .expect("tray");
    #[cfg(windows)]
    TrayIconEvent::set_event_handler(Some(|e| { let _ = slint::invoke_from_event_loop(move || on_tray(e)); }));
    #[cfg(not(windows))]
    let tray = linux_tray();

    let tx = session::spawn(|s| { let _ = slint::invoke_from_event_loop(move || with_app(|a| a.apply(s))); });
    APP.with(|a| *a.borrow_mut() = Some(App {
        main: main.clone_strong(), #[cfg(windows)] panel, tray, tx, modes: AncModes::default(), last_level: None, tr,
        snap: Snapshot::default(), gains, eq_pending: Default::default(), edit_key: None, plot: (0.0, 0.0), log_shown: 0, has: Has::ALL,
    }));
    main.show().expect("show");
    with_app(|a| theme::apply(a));
    let dot_matrix = load_settings()["dot_matrix"].as_bool().unwrap_or(false);
    let scale = main.window().scale_factor();
    with_app(|a| a.set_style(dot_matrix, scale));
    update::setup(&main);
    with_app(report::show_crash);
    // Closing the window hides it; the app lives in the tray until Quit.
    slint::run_event_loop_until_quit().expect("event loop");
}
