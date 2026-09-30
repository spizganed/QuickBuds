//! Shares the phone app's resources instead of copying them:
//! - icons: Android vector drawables -> SVG strings (`icons.rs`)
//! - strings: `values*/strings.xml` for the keys below -> one table per locale (`strings.rs`)

use std::{env, fmt::Write, fs, path::Path};

const RES: &str = "../app/src/main/res";

const ICONS: &[&str] = &[
    "ic_bud_left", "ic_bud_right", "ic_case", "ic_earbud", "ic_low_latency",
    "ic_mode_off", "ic_mode_anc_medium", "ic_mode_adaptive", "ic_mode_transparency",
    "ic_mode_anc_low", "ic_mode_anc_high", "ic_mode_anc_smart",
    "ic_layout", "ic_equalizer", "ic_gesture", "ic_hearing", "ic_devices", "ic_settings_cog",
];

const STRINGS: &[&str] = &[
    "conn_on", "conn_action_connect", "conn_action_disconnect", "conn_connecting", "dual_not_connected",
    "status_left", "status_right", "status_case", "anc_section", "anc_seg_off", "anc_seg_anc",
    "anc_seg_adapt", "anc_seg_trans", "anc_mode_low", "anc_mode_medium", "anc_mode_high",
    "anc_mode_smart", "widget_low_latency",
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
            assert!(attr(tag, "scaleX").is_none() && attr(tag, "scaleY").is_none(), "group scale not supported");
            let (px, py) = (attr(tag, "pivotX").unwrap_or("0"), attr(tag, "pivotY").unwrap_or("0"));
            let (tx, ty) = (attr(tag, "translateX").unwrap_or("0"), attr(tag, "translateY").unwrap_or("0"));
            let r = attr(tag, "rotation").unwrap_or("0");
            write!(svg, r#"<g transform="translate({tx} {ty}) rotate({r} {px} {py})">"#).unwrap();
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
