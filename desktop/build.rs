//! Shares the phone app's resources instead of copying them:
//! - icons: Android vector drawables -> SVG strings (`icons.rs`)
//! - strings: `values*/strings.xml` for the keys below -> one table per locale (`strings.rs`)

use std::{env, fmt::Write, fs, path::Path};

const RES: &str = "../app/src/main/res";

const ICONS: &[&str] = &[
    "ic_bud_left", "ic_bud_right", "ic_case", "ic_earbud", "ic_low_latency",
    "ic_mode_off", "ic_mode_anc_medium", "ic_mode_adaptive", "ic_mode_transparency",
    "ic_mode_anc_low", "ic_mode_anc_high", "ic_mode_anc_smart",
    "ic_layout", "ic_equalizer", "ic_gesture", "ic_hearing", "ic_devices", "ic_settings_cog", "ic_dev_tools",
    "ic_chevron_right", "ic_plus", "ic_volume", "ic_power", "ic_transparency", "ic_info", "ic_find_buds", "ic_volume_off",
];

const STRINGS: &[&str] = &[
    "conn_on", "conn_action_connect", "conn_action_disconnect", "conn_connecting", "dual_not_connected",
    "status_left", "status_right", "status_case", "anc_section", "anc_seg_off", "anc_seg_anc",
    "anc_seg_adapt", "anc_seg_trans", "anc_mode_low", "anc_mode_medium", "anc_mode_high",
    "anc_mode_smart", "widget_low_latency",
    "status_in_ear", "status_in_case", "status_out", "row_game_title", "row_game_sub", "row_eq_title", "row_eq_sub",
    "eq_title", "eq_not_connected", "eq_recommended", "eq_basswave", "eq_basswave_sub", "eq_custom",
    "eq_rename", "eq_delete", "eq_add", "eq_save", "widget_style_title", "widget_style_classic", "widget_style_nothing",
    "earbuds_title", "earbuds_section_features", "earbuds_section_about", "row_firmware_title", "firmware_dialog_title",
    "firmware_dialog_body", "dialog_close", "dialog_cancel", "dual_add_ok",
    "row_vocal_title", "row_vocal_sub", "row_game_sound_title", "row_game_sound_sub", "row_smart_volume_title",
    "row_smart_volume_sub", "row_adaptive_volume_title", "row_adaptive_volume_sub", "row_adaptive_ear_title",
    "row_adaptive_ear_sub", "row_sleep_title", "row_sleep_sub", "row_speech_title", "row_speech_sub",
    "row_hearing_optimize_title", "row_hearing_optimize_sub", "hearing_optimize_confirm", "row_long_press_volume_title",
    "row_long_press_volume_sub", "row_head_motion_title", "row_head_motion_sub", "row_swift_pair_title",
    "row_swift_pair_sub", "row_power_saving_title", "row_power_saving_sub", "power_saving_confirm",
    "wear_firmware_title", "wear_firmware_sub", "row_find_title", "row_find_sub", "find_title", "find_hint", "find_stop",
    "find_play", "find_warn_title", "find_warn_msg", "find_warn_play",
    "earbuds_section_sounds", "row_alert_title", "tap_level_title", "tap_level_hint", "tap_level_warning",
    "game_sound_type_title", "game_sound_type_shooter", "game_sound_type_peace", "head_motion_type_title",
    "head_motion_nod", "head_motion_shake", "gesture_bud_left", "gesture_bud_right", "gesture_done", "fit_title",
    "fit_sub", "fit_hint", "fit_play", "fit_playing", "fit_keep", "fit_good", "fit_average", "fit_poor", "fit_perfect",
    "fit_adjust", "fit_adjust_tips", "fit_both", "fit_left", "fit_right", "fit_again", "fit_insert", "fit_failed",
    "pnc_title", "pnc_sub", "pnc_wear", "pnc_stored_title", "pnc_stored_body", "pnc_use", "pnc_test_again", "pnc_test_title", "pnc_test_body", "pnc_start", "pnc_testing", "pnc_done", "pnc_failed", "pnc_retry", "pnc_fail_quiet", "pnc_fail_fit", "pnc_fail_wind", "pnc_fail_still", "pnc_fail_audio",
    "model_title", "model_auto", "model_detected", "model_not_detected",
    "dual_title", "dual_switch_sub", "dual_section_devices", "dual_section_all", "dual_connected", "dual_connected_this",
    "dual_add_title", "dual_add_message", "dual_connect_q", "dual_disconnect_q", "dual_preferred", "dual_preferred_auto",
    "dual_preferred_sub",
    "row_golden_title", "row_golden_sub", "golden_profiles", "golden_none", "golden_test_row", "golden_test_sub",
    "golden_test_title", "golden_prepare", "golden_start", "golden_scanning", "golden_scan_hint", "golden_left",
    "golden_right", "golden_step", "golden_next", "golden_finish", "golden_loud", "golden_creating", "golden_saved",
    "golden_saved_msg", "golden_stopped", "golden_stopped_music", "golden_stopped_wear", "golden_scan_failed",
    "golden_again", "golden_boost", "eq_delete_confirm",
    // Built-in preset names (`protocol::eq_builtins`)
    "eq_balanced", "eq_clear_vocals", "eq_bass", "eq_classic", "eq_nature_balance", "eq_dynamic_bass",
    "eq_bass_boost", "eq_clear", "eq_gentle", "eq_default", "eq_dyn_simple", "eq_dyn_warm", "eq_dyn_punchy",
    "eq_dyn_real", "eq_hisaishi", "eq_bold", "eq_enco_x_classic", "eq_reno_dawn", "eq_hans_zimmer",
    "eq_natural_inspiration", "eq_reno_sunrise", "eq_punchy", "eq_spacious", "eq_reno_galaxy", "eq_ultimate",
    "eq_hd_clarity", "eq_pure_vocals", "eq_thundering_bass", "eq_dyn_featured", "eq_galactic", "eq_vibrant",
    "eq_dyn_vocal", "eq_clear_crisp",
];

fn attr<'a>(tag: &'a str, name: &str) -> Option<&'a str> {
    let key = format!("android:{name}=\"");
    let start = tag.find(&key)? + key.len();
    Some(&tag[start..start + tag[start..].find('"')?])
}

/// Only what these icons use: paths with fill / stroke, groups with rotation / translation.
/// Anything else fails the build rather than drawing a wrong icon.
fn vector_to_svg(xml: &str) -> String {
    assert!(!xml.contains("<clip-path"), "clip-path not supported");
    let vw = attr(xml, "viewportWidth").unwrap();
    let vh = attr(xml, "viewportHeight").unwrap();
    let mut svg = format!(r#"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {vw} {vh}" width="{vw}" height="{vh}">"#);
    let body = &xml[xml.find("<vector").unwrap()..];
    for (i, tag) in body.split('<').enumerate().skip(1) {
        if tag.starts_with("/group") { svg += "</g>"; continue; }
        if tag.starts_with("group") {
            let tag = &tag[..tag.find('>').unwrap()];
            let a = |n| attr(tag, n).unwrap_or("0");
            let s = |n| attr(tag, n).unwrap_or("1");
            // Android's order: translate, then rotate and scale around the pivot.
            let (px, py) = (a("pivotX"), a("pivotY"));
            write!(svg, r#"<g transform="translate({} {}) translate({px} {py}) rotate({}) scale({} {}) translate(-{px} -{py})">"#,
                a("translateX"), a("translateY"), a("rotation"), s("scaleX"), s("scaleY")).unwrap();
            continue;
        }
        if !tag.starts_with("path") { assert!(i == 0 || !tag.starts_with(char::is_alphabetic) || tag.starts_with("vector"), "unsupported <{tag}"); continue; }
        let tag = &tag[..tag.find("/>").unwrap()];
        let fill = attr(tag, "fillColor").unwrap_or("none");
        write!(svg, r#"<path d="{}" fill="{fill}""#, attr(tag, "pathData").unwrap()).unwrap();
        if attr(tag, "fillType") == Some("evenOdd") { svg += r#" fill-rule="evenodd""#; }
        if let Some(c) = attr(tag, "strokeColor") {
            write!(svg, r#" stroke="{c}" stroke-width="{}""#, attr(tag, "strokeWidth").unwrap_or("1")).unwrap();
            if let Some(v) = attr(tag, "strokeLineCap") { write!(svg, r#" stroke-linecap="{v}""#).unwrap(); }
            if let Some(v) = attr(tag, "strokeLineJoin") { write!(svg, r#" stroke-linejoin="{v}""#).unwrap(); }
        }
        svg += "/>";
    }
    svg + "</svg>"
}

/// `<string name="key">text</string>` with Android's escapes undone.
fn android_string(xml: &str, key: &str) -> Option<String> {
    let open = format!("<string name=\"{key}\"");
    let start = xml.find(&open)?;
    let body_start = start + xml[start..].find('>')? + 1;
    let body = &xml[body_start..body_start + xml[body_start..].find("</string>")?];
    Some(body.trim_matches('"').replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">"))
}

fn main() {
    let out = env::var("OUT_DIR").unwrap();

    let mut icons = String::new();
    for name in ICONS {
        let path = format!("{RES}/drawable/{name}.xml");
        println!("cargo:rerun-if-changed={path}");
        let svg = vector_to_svg(&fs::read_to_string(&path).unwrap());
        writeln!(icons, "pub const {}: &str = {svg:?};", name.trim_start_matches("ic_").to_uppercase()).unwrap();
    }
    // The launcher icon: the adaptive foreground on its black background, cut to the 72-unit safe
    // zone with the rounded-square mask.
    let fg_path = format!("{RES}/drawable/ic_launcher_foreground.xml");
    println!("cargo:rerun-if-changed={fg_path}");
    let fg = vector_to_svg(&fs::read_to_string(&fg_path).unwrap());
    let body = &fg[fg.find('>').unwrap() + 1..];
    let launcher = format!(r##"<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="72" height="72"><rect x="18" y="18" width="72" height="72" rx="16" fill="#000000"/>{body}"##);
    writeln!(icons, "pub const LAUNCHER: &str = {launcher:?};").unwrap();
    fs::write(Path::new(&out).join("icons.rs"), icons).unwrap();

    // (locale tag, values) per `values-*` folder; "" is the English default.
    let mut table = String::from("pub const KEYS: &[&str] = &[");
    for k in STRINGS { write!(table, "{k:?},").unwrap(); }
    table += "];\npub const LOCALES: &[(&str, &[Option<&str>])] = &[\n";
    let mut dirs: Vec<_> = fs::read_dir(RES).unwrap().flatten()
        .filter_map(|e| e.file_name().into_string().ok())
        .filter(|n| n == "values" || n.starts_with("values-") && Path::new(&format!("{RES}/{n}/strings.xml")).exists())
        .collect();
    dirs.sort();
    for dir in dirs {
        let path = format!("{RES}/{dir}/strings.xml");
        println!("cargo:rerun-if-changed={path}");
        let xml = fs::read_to_string(&path).unwrap();
        // values-zh-rCN -> zh-CN
        let tag = dir.trim_start_matches("values").trim_start_matches('-').replace("-r", "-");
        write!(table, "({tag:?}, &[").unwrap();
        for k in STRINGS { write!(table, "{:?},", android_string(&xml, k)).unwrap(); }
        table += "]),\n";
    }
    table += "];\n";
    fs::write(Path::new(&out).join("strings.rs"), table).unwrap();

    slint_build::compile("ui/app.slint").unwrap();
}
