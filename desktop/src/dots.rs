//! The Dot matrix style (the phone's `DotArt` and the widget's dot rings): a drawing is rendered per cell, then
//! every cell it covers becomes one round dot. Images are cached per size and colour.

use crate::icons;
use resvg::tiny_skia::{self as sk, BlendMode, FillRule, Paint, Path, PathBuilder, Pixmap, Rect, Stroke, Transform};
use slint::{Color, Image, Rgba8Pixel, SharedPixelBuffer};
use std::cell::RefCell;
use std::collections::HashMap;

/// Dot pitch in logical px, as the phone's dp (`DotArt.PITCH_DP`, `ICON_PITCH_DP`).
pub const PITCH: f32 = 2.2;
const ICON_PITCH: f32 = 1.2;
/// The battery rings' cells across (the widget's `RING_CELLS`).
const RING_CELLS: u32 = 42;
/// The one knob every dot slider uses (`DotArt.KNOB`).
const KNOB: [&str; 7] = ["..###..", ".#...#.", "#.....#", "#.....#", "#.....#", ".#...#.", "..###.."];

/// The pitch in whole physical px: a fractional one put every dot at a different sub-pixel offset.
pub fn pitch_px(scale: f32, dp: f32) -> f32 { (dp * scale).round().max(2.0) }

thread_local! { static CACHE: RefCell<HashMap<String, Image>> = RefCell::new(HashMap::new()); }

fn cached(key: String, make: impl FnOnce() -> Option<Pixmap>) -> Image {
    if let Some(i) = CACHE.with(|c| c.borrow().get(&key).cloned()) { return i; }
    let img = make().map_or_else(Image::default, |pm| Image::from_rgba8_premultiplied(
        SharedPixelBuffer::<Rgba8Pixel>::clone_from_slice(pm.data(), pm.width(), pm.height())));
    CACHE.with(|c| {
        let mut c = c.borrow_mut();
        // Window resizes make new card sizes: the boxes go when it is full, the icons and rings (slow to draw) stay.
        // ponytail: an LRU if it ever shows.
        if c.len() > 300 { c.retain(|k, _| !k.starts_with("box ")); }
        c.insert(key, img.clone());
    });
    img
}

fn sk_color(c: Color) -> sk::Color { sk::Color::from_rgba8(c.red(), c.green(), c.blue(), c.alpha()) }

fn paint(c: sk::Color, aa: bool) -> Paint<'static> {
    let mut p = Paint::default();
    p.set_color(c);
    p.anti_alias = aa;
    p
}

fn rrect(x: f32, y: f32, w: f32, h: f32, r: f32) -> Option<Path> {
    let r = r.min(w / 2.0).min(h / 2.0).max(0.0);
    let k = r * 0.5523;
    let mut p = PathBuilder::new();
    p.move_to(x + r, y);
    p.line_to(x + w - r, y);
    p.cubic_to(x + w - r + k, y, x + w, y + r - k, x + w, y + r);
    p.line_to(x + w, y + h - r);
    p.cubic_to(x + w, y + h - r + k, x + w - r + k, y + h, x + w - r, y + h);
    p.line_to(x + r, y + h);
    p.cubic_to(x + r - k, y + h, x, y + h - r + k, x, y + h - r);
    p.line_to(x, y + r);
    p.cubic_to(x, y + r - k, x + r - k, y, x + r, y);
    p.close();
    p.finish()
}

/// Cells of `src` (`cols` x `rows` cells of `ss` px) as dots `pitch` px apart. A cell is lit when its mean
/// alpha (over its middle half with `centre`) reaches `min`; its dot takes the most opaque sample's colour,
/// opaque with `ss > 1` (shapes), with its own alpha at one sample per cell (boxes: a faint fill, faint dots).
/// `despeckle`: a lone dot is left out.
fn matrix(src: &Pixmap, cols: u32, rows: u32, ss: u32, pitch: f32, min: u32, centre: bool, despeckle: bool) -> Option<Pixmap> {
    let px = src.pixels();
    let (a, b) = if centre && ss > 1 { (ss / 4, ss * 3 / 4) } else { (0, ss) };
    let mut cells: Vec<Option<sk::Color>> = vec![None; (cols * rows) as usize];
    for y in 0..rows {
        for x in 0..cols {
            let (mut sum, mut best) = (0u32, sk::PremultipliedColorU8::TRANSPARENT);
            for j in a..b {
                for i in a..b {
                    let v = px[((y * ss + j) * src.width() + x * ss + i) as usize];
                    sum += v.alpha() as u32;
                    if v.alpha() > best.alpha() { best = v; }
                }
            }
            if sum / ((b - a) * (b - a)) < min { continue; }
            let c = best.demultiply();
            let alpha = if ss > 1 { 255 } else { c.alpha() };
            cells[(y * cols + x) as usize] = Some(sk::Color::from_rgba8(c.red(), c.green(), c.blue(), alpha));
        }
    }
    let on = |x: i64, y: i64| x >= 0 && y >= 0 && x < cols as i64 && y < rows as i64 && cells[(y * cols as i64 + x) as usize].is_some();
    let mut out = Pixmap::new((cols as f32 * pitch).round().max(1.0) as u32, (rows as f32 * pitch).round().max(1.0) as u32)?;
    // A whole-px pitch: one dot per colour is drawn once and copied into each cell (a dot stays inside its
    // cell, so nothing blends). A path per dot cost ~70 ms per card on each window resize.
    let whole = pitch.fract() == 0.0;
    let mut stamps: HashMap<[u8; 4], Pixmap> = HashMap::new();
    for y in 0..rows as i64 {
        for x in 0..cols as i64 {
            let Some(c) = cells[(y * cols as i64 + x) as usize] else { continue };
            if despeckle && !on(x - 1, y) && !on(x + 1, y) && !on(x, y - 1) && !on(x, y + 1) { continue; }
            if whole {
                let u = c.to_color_u8();
                let p = pitch as u32;
                let stamp = match stamps.entry([u.red(), u.green(), u.blue(), u.alpha()]) {
                    std::collections::hash_map::Entry::Occupied(e) => e.into_mut(),
                    std::collections::hash_map::Entry::Vacant(e) => e.insert(dot(c, pitch, 0.0, 0.0, Pixmap::new(p, p)?)),
                };
                let (w, ox, oy) = (out.width() as usize, x as usize * p as usize, y as usize * p as usize);
                let (dst, src) = (out.data_mut(), stamp.data());
                for r in 0..p as usize {
                    let (d, s) = (((oy + r) * w + ox) * 4, r * p as usize * 4);
                    dst[d..d + p as usize * 4].copy_from_slice(&src[s..s + p as usize * 4]);
                }
            } else {
                out = dot(c, pitch, x as f32 * pitch, y as f32 * pitch, out);
            }
        }
    }
    Some(out)
}

/// One dot of colour `c` in the cell at `x`, `y` px.
fn dot(c: sk::Color, pitch: f32, x: f32, y: f32, mut pm: Pixmap) -> Pixmap {
    if let Some(d) = PathBuilder::from_circle(x + 0.5 * pitch, y + 0.5 * pitch, pitch * 0.42) {
        pm.fill_path(&d, &paint(c, true), FillRule::Winding, Transform::identity(), None);
    }
    pm
}

/// A card, button or row (`DotArt.Box`): `w` x `h` physical px snapped down to whole cells, the outline one
/// cell of `stroke` dots, the fill dots a cell inside.
pub fn dot_box(w: f32, h: f32, fill: Color, stroke: Color, radius: f32, pitch: f32) -> Image {
    let (cols, rows) = ((w / pitch) as u32, (h / pitch) as u32);
    cached(format!("box {cols} {rows} {fill:?} {stroke:?} {radius} {pitch}"), || {
        let mut pm = Pixmap::new(cols.max(1), rows.max(1))?;
        let t = Transform::from_scale(1.0 / pitch, 1.0 / pitch);
        let (bw, bh) = (cols as f32 * pitch, rows as f32 * pitch);
        pm.fill_path(&rrect(0.0, 0.0, bw, bh, radius)?, &paint(sk_color(stroke), false), FillRule::Winding, t, None);
        let mut inner = paint(sk_color(fill), false);
        inner.blend_mode = BlendMode::Source;
        if let Some(p) = rrect(pitch, pitch, bw - 2.0 * pitch, bh - 2.0 * pitch, radius - pitch) {
            pm.fill_path(&p, &inner, FillRule::Winding, t, None);
        }
        matrix(&pm, cols, rows, 1, pitch, 50, false, false)
    })
}

/// `svg` rendered into a `w` x `h` px pixmap, every pixel in `tint`.
fn render_svg(svg: &str, w: u32, h: u32, tint: sk::Color) -> Option<Pixmap> {
    let tree = resvg::usvg::Tree::from_str(svg, &Default::default()).ok()?;
    let mut pm = Pixmap::new(w.max(1), h.max(1))?;
    let s = tree.size();
    resvg::render(&tree, Transform::from_scale(w as f32 / s.width(), h as f32 / s.height()), &mut pm.as_mut());
    let t = tint.to_color_u8();
    for p in pm.pixels_mut() {
        let a = p.alpha();
        let m = |c: u8| (c as u32 * a as u32 / 255) as u8;
        *p = sk::PremultipliedColorU8::from_rgba(m(t.red()), m(t.green()), m(t.blue()), a).unwrap();
    }
    Some(pm)
}

/// A row or button icon as white dots (tinted by the Image's `colorize`), `size` logical px tall.
pub fn icon(svg: &str, size: f32, scale: f32) -> Image {
    let pitch = pitch_px(scale, ICON_PITCH);
    let rows = ((size * scale / pitch).round() as u32).max(5);
    cached(format!("icon {svg} {rows} {pitch}"), || {
        let tree = resvg::usvg::Tree::from_str(svg, &Default::default()).ok()?;
        let cols = ((rows as f32 * tree.size().width() / tree.size().height()).round() as u32).max(1);
        let pm = render_svg(svg, cols * 8, rows * 8, sk::Color::WHITE)?;
        // Transparency's dashed rings: only cells well inside a dash, so each dash is one clean dot.
        if svg == icons::MODE_TRANSPARENCY { matrix(&pm, cols, rows, 8, pitch, 220, true, false) }
        else { matrix(&pm, cols, rows, 8, pitch, 90, false, false) }
    })
}

/// A battery ring (the widget's `dotRing`): track, accent arc from 12 o'clock, and the glyph of `slot`
/// (0 left, 1 case, 2 right; -1 none) in `tint`, `size` physical px square.
pub fn ring(size: f32, level: i32, slot: i32, tint: Color, accent: Color, track: Color) -> Image {
    cached(format!("ring {size} {level} {slot} {tint:?} {accent:?} {track:?}"), || {
        let (n, ss) = (RING_CELLS, 8);
        let s = (n * ss) as f32;
        let mut pm = Pixmap::new(n * ss, n * ss)?;
        let w = s * 2.6 / n as f32;
        let r = s / 2.0 - w / 2.0;
        let stroke = Stroke { width: w, line_cap: sk::LineCap::Round, ..Default::default() };
        let circle = PathBuilder::from_circle(s / 2.0, s / 2.0, r)?;
        pm.stroke_path(&circle, &paint(sk_color(track), true), &stroke, Transform::identity(), None);
        if (1..=100).contains(&level) {
            let mut arc = PathBuilder::new();
            let steps = level.max(2) * 2;
            for i in 0..=steps {
                let a = (i as f32 / steps as f32 * level as f32 / 100.0 * 360.0 - 90.0).to_radians();
                let (x, y) = (s / 2.0 + r * a.cos(), s / 2.0 + r * a.sin());
                if i == 0 { arc.move_to(x, y) } else { arc.line_to(x, y) }
            }
            pm.stroke_path(&arc.finish()?, &paint(sk_color(accent), true), &stroke, Transform::identity(), None);
        }
        let case = slot == 1;
        if slot < 0 { return matrix(&pm, n, n, ss, size / n as f32, 128, true, true); }
        // The glyph's box: the largest whose corners clear the ring, a little bigger for the thin dot ring.
        let (svg, ratio, fill) = match slot {
            0 => (icons::BUD_LEFT, 176.0 / 272.0, 1.18),
            1 => (icons::CASE, 496.0 / 400.0, 1.22),
            _ => (icons::BUD_RIGHT, 176.0 / 272.0, 1.18),
        };
        let bh = s * if case { 0.46 } else { 0.6 } * fill;
        let bw = s * if case { 0.62 } else { 0.42 } * fill;
        let (gw, gh) = if ratio < bw / bh { (bh * ratio, bh) } else { (bw, bw / ratio) };
        let (gx, gy) = (((s - gw) / 2.0).round(), ((s - gh) / 2.0).round());
        let glyph = render_svg(svg, gw as u32, gh as u32, sk_color(tint))?;
        pm.draw_pixmap(gx as i32, gy as i32, glyph.as_ref(), &sk::PixmapPaint::default(), Transform::identity(), None);
        if case {
            // The LED and the lid cut are under a cell tall: clear their cells so they always show.
            let cell = |v: f32| (v / ss as f32).floor() * ss as f32;
            let mut clear = paint(sk::Color::TRANSPARENT, false);
            clear.blend_mode = BlendMode::Clear;
            let led = Rect::from_xywh(cell(gx + 247.6 / 496.0 * gw), cell(gy + 316.5 / 400.0 * gh), ss as f32, ss as f32);
            let lid = Rect::from_xywh(gx, cell(gy + 144.8 / 400.0 * gh), gw, ss as f32);
            for r in [led, lid].into_iter().flatten() { pm.fill_rect(r, &clear, Transform::identity(), None); }
        }
        matrix(&pm, n, n, ss, size / n as f32, 128, true, !case)
    })
}

/// The EQ curve (SVG path `d` in a `w` x `h` logical plot) as `color` dots at the window's pitch: rendered at
/// 4 samples a cell, a cell half covered is a dot.
pub fn curve(d: &str, (w, h): (f32, f32), color: Color, scale: f32) -> Image {
    let pitch = pitch_px(scale, PITCH);
    let (cols, rows) = ((w * scale / pitch) as u32, (h * scale / pitch) as u32);
    if d.is_empty() || cols == 0 || rows == 0 { return Image::default(); }
    cached(format!("curve {d} {cols} {rows} {color:?}"), || {
        // The stroke two cells wide, so a steep stretch stays one unbroken line of dots.
        let svg = format!(r##"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}"><path d="{d}" fill="none" stroke="#fff" stroke-width="{}" stroke-linecap="round"/></svg>"##,
            2.0 * pitch / scale);
        let pm = render_svg(&svg, cols * 4, rows * 4, sk_color(color))?;
        matrix(&pm, cols, rows, 4, pitch, 128, false, false)
    })
}

/// The slider knob: a fixed ring of dots in `ring`, filled with `fill`.
pub fn knob(ring: Color, fill: Color, pitch: f32) -> Image {
    cached(format!("knob {ring:?} {fill:?} {pitch}"), || {
        let mut pm = Pixmap::new(7, 7)?;
        for (y, row) in KNOB.iter().enumerate() {
            let (first, last) = (row.find('#').unwrap(), row.rfind('#').unwrap());
            for (x, ch) in row.chars().enumerate() {
                let c = if ch == '#' { ring } else if (first..=last).contains(&x) && (1..=5).contains(&y) { fill } else { continue };
                pm.fill_rect(Rect::from_xywh(x as f32, y as f32, 1.0, 1.0)?, &paint(sk_color(c), false), Transform::identity(), None);
            }
        }
        matrix(&pm, 7, 7, 1, pitch, 50, false, false)
    })
}

/// A round disc of `n` x `n` cells (the switch's thumb, a colour swatch): `color`, its outer cells `edge`.
pub fn disc(n: u32, color: Color, edge: Color, pitch: f32) -> Image {
    cached(format!("disc {n} {color:?} {edge:?} {pitch}"), || {
        let mut pm = Pixmap::new(n.max(1), n.max(1))?;
        let m = (n as f32 - 1.0) / 2.0;
        let inside = |x: i64, y: i64| {
            let (dx, dy) = (x as f32 - m, y as f32 - m);
            x >= 0 && y >= 0 && dx * dx + dy * dy <= (n * n) as f32 / 4.0
        };
        for y in 0..n as i64 {
            for x in 0..n as i64 {
                if !inside(x, y) { continue; }
                let rim = !inside(x - 1, y) || !inside(x + 1, y) || !inside(x, y - 1) || !inside(x, y + 1);
                let c = if rim { edge } else { color };
                pm.fill_rect(Rect::from_xywh(x as f32, y as f32, 1.0, 1.0)?, &paint(sk_color(c), false), Transform::identity(), None);
            }
        }
        matrix(&pm, n, n, 1, pitch, 50, false, false)
    })
}

/// A colour slider's track (`ColorSliderView`): a pill five cells tall, `w` physical px wide, that shows what
/// `channel` (0 hue, 1 saturation, 2 brightness) does to the colour `hue`, `sat`, `val`.
pub fn slider(w: f32, channel: i32, hue: f32, sat: f32, val: f32, pitch: f32) -> Image {
    let (cols, rows) = ((w / pitch) as u32, 5u32);
    // "box": a resize or a drag makes many; the cache drops these first.
    cached(format!("box slider {cols} {channel} {hue} {sat} {val} {pitch}"), || {
        let mut pm = Pixmap::new(cols.max(1), rows)?;
        let lerp = |a: Color, b: Color, t: f32| {
            let m = |x: u8, y: u8| (x as f32 + (y as f32 - x as f32) * t).round() as u8;
            sk::ColorU8::from_rgba(m(a.red(), b.red()), m(a.green(), b.green()), m(a.blue(), b.blue()), 255)
        };
        let r = rows as f32 / 2.0;
        for x in 0..cols {
            let t = x as f32 / (cols.max(2) - 1) as f32;
            let c = match channel {
                0 => lerp(Color::from_hsva(t * 360.0, 0.85, 0.95, 1.0), Color::default(), 0.0),
                1 => lerp(Color::from_hsva(hue, 0.0, val, 1.0), Color::from_hsva(hue, 1.0, val, 1.0), t),
                _ => lerp(Color::from_hsva(hue, sat, 0.0, 1.0), Color::from_hsva(hue, sat, 1.0, 1.0), t),
            };
            let cx = (x as f32 + 0.5).clamp(r, cols as f32 - r);
            for y in 0..rows {
                let (dx, dy) = (x as f32 + 0.5 - cx, y as f32 + 0.5 - r);
                if dx * dx + dy * dy <= r * r { pm.pixels_mut()[(y * cols + x) as usize] = c.premultiply(); }
            }
        }
        matrix(&pm, cols, rows, 1, pitch, 50, false, false)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn size(i: &Image) -> (u32, u32) { let s = i.size(); (s.width, s.height) }

    #[test]
    fn shapes_snap_to_cells() {
        // 100 x 41 px at a 4 px pitch: 25 x 10 cells.
        assert_eq!(size(&dot_box(100.0, 41.0, Color::from_rgb_u8(40, 40, 40), Color::from_rgb_u8(90, 90, 90), 16.0, 4.0)), (100, 40));
        assert_eq!(size(&knob(Color::from_rgb_u8(255, 0, 0), Color::from_rgb_u8(0, 0, 0), 3.0)), (21, 21));
        assert_eq!(size(&ring(84.0, 50, 1, Color::from_rgb_u8(255, 255, 255), Color::from_rgb_u8(255, 0, 0), Color::from_rgb_u8(80, 80, 80))), (84, 84));
        assert!(size(&icon(icons::EQUALIZER, 24.0, 1.0)).1 >= 20);
        assert_eq!(pitch_px(1.0, PITCH), 2.0);
        assert_eq!(pitch_px(2.0, PITCH), 4.0);
    }

    #[test]
    fn copied_dots_match_drawn_dots() {
        // Copied dots and one path per dot give the same pixels.
        let mut src = Pixmap::new(30, 12).unwrap();
        src.fill_rect(Rect::from_xywh(2.0, 2.0, 20.0, 8.0).unwrap(), &paint(sk::Color::from_rgba8(200, 30, 30, 255), false), Transform::identity(), None);
        src.fill_rect(Rect::from_xywh(24.0, 0.0, 6.0, 12.0).unwrap(), &paint(sk::Color::from_rgba8(30, 30, 200, 120), false), Transform::identity(), None);
        let fast = matrix(&src, 30, 12, 1, 4.0, 50, false, false).unwrap();
        let mut slow = Pixmap::new(120, 48).unwrap();
        for y in 0..12 {
            for x in 0..30 {
                let v = src.pixel(x, y).unwrap().demultiply();
                if v.alpha() >= 50 { slow = dot(sk::Color::from_rgba8(v.red(), v.green(), v.blue(), v.alpha()), 4.0, x as f32 * 4.0, y as f32 * 4.0, slow); }
            }
        }
        assert_eq!(fast.data(), slow.data());
    }
}
