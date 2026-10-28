//! The quick panel: the small window that a right click on the Windows tray icon opens. It sits next to
//! the click point, above and left of it, and closes when it loses focus or on Escape.
//!
//! The place of the panel is pure and stays outside a `cfg` gate, so a test on Linux checks it.

/// The panel's size in logical points, as the Slint panel.
pub const SIZE: [f32; 2] = [300.0, 250.0];

/// The panel's top-left corner in physical pixels, for a click at `click` in physical pixels.
/// The panel goes above and left of the click point. It flips below or right of the point when that
/// side is off screen, and it never leaves the screen.
pub fn pos(click: (f32, f32), size: (f32, f32), screen: (f32, f32)) -> (f32, f32) {
    // Before the point, else after it; then keep the panel on the screen.
    let place = |p: f32, s: f32, max: f32| (if p - s >= 0.0 { p - s } else { p }).clamp(0.0, (max - s).max(0.0));
    (place(click.0, size.0, screen.0), place(click.1, size.1, screen.1))
}

#[cfg(test)]
mod tests {
    use super::*;

    const PANEL: (f32, f32) = (300.0, 250.0);
    const SCREEN: (f32, f32) = (1920.0, 1080.0);

    #[test]
    fn above_and_left_of_the_click() {
        assert_eq!(pos((1500.0, 800.0), PANEL, SCREEN), (1200.0, 550.0));
    }

    #[test]
    fn below_and_right_of_a_click_near_the_origin() {
        // A taskbar at the top, or the panel button of a taskbar on the left.
        assert_eq!(pos((100.0, 60.0), PANEL, SCREEN), (100.0, 60.0));
    }

    #[test]
    fn one_side_flips_on_its_own() {
        // A taskbar at the top: the panel goes below the click, and still left of it.
        assert_eq!(pos((1500.0, 60.0), PANEL, SCREEN), (1200.0, 60.0));
        // A taskbar on the left: the panel goes right of the click, and still above it.
        assert_eq!(pos((40.0, 800.0), PANEL, SCREEN), (40.0, 550.0));
    }

    #[test]
    fn the_panel_stays_on_a_small_screen() {
        assert_eq!(pos((100.0, 100.0), PANEL, (350.0, 300.0)), (50.0, 50.0));
    }

    #[test]
    fn a_screen_smaller_than_the_panel_gives_the_origin() {
        assert_eq!(pos((100.0, 100.0), PANEL, (200.0, 200.0)), (0.0, 0.0));
    }
}
