//! The tray icon: the launcher icon, and the text the menu and the tooltip show. The GTK thread and the
//! Windows icon live below; the text rules stay pure so a test can check them.

use crate::session::{Snapshot, Status};
use crate::t;
use crate::types::{self, AncModes};

/// The model name, else the app name while no buds named themselves.
fn name_of(s: &Snapshot, tr: &[String]) -> String {
    if s.name.is_empty() { t(tr, "app_name").to_string() } else { s.name.clone() }
}

/// The short battery line of the menu: "L:80 C:40 R:75". The model name while the buds are not connected.
pub fn battery_line(s: &Snapshot, tr: &[String]) -> String {
    if s.status != Status::On { return name_of(s, tr); }
    let line: Vec<String> = [("L", 0), ("C", 2), ("R", 1)].iter()
        .filter_map(|(k, i)| s.battery[*i].map(|b| format!("{k}:{}", b.0))).collect();
    if line.is_empty() { name_of(s, tr) } else { line.join(" ") }
}

/// The Windows tooltip: the model name and "L 80 · R 75 · Case 40". The app name while the buds are not connected.
pub fn tooltip(s: &Snapshot, tr: &[String]) -> String {
    if s.status != Status::On { return t(tr, "app_name").to_string(); }
    let parts: Vec<String> = [("L", 0), ("R", 1), (t(tr, "status_case"), 2)].iter()
        .filter_map(|(k, i)| s.battery[*i].map(|b| format!("{k} {}", b.0))).collect();
    if parts.is_empty() { name_of(s, tr) } else { format!("{}\n{}", name_of(s, tr), parts.join(" · ")) }
}

/// The modes of a menu, in the order the app shows them: (mode name, string key, checked).
/// Only the modes the model offers; the current one is checked.
pub fn mode_items(modes: &AncModes, anc: Option<&str>) -> Vec<(&'static str, &'static str, bool)> {
    const ITEMS: [(&str, &str); 7] = [
        (types::OFF, "anc_seg_off"),
        ("ANC-Light", "anc_mode_low"),
        ("ANC-Medium", "anc_mode_medium"),
        ("ANC-Deep", "anc_mode_high"),
        ("ANC-Smart", "anc_mode_smart"),
        (types::ADAPTIVE, "anc_seg_adapt"),
        (types::TRANSPARENCY, "anc_seg_trans"),
    ];
    ITEMS.iter().filter(|(m, _)| modes.supports(m))
        .map(|(m, key)| (*m, *key, anc == Some(*m))).collect()
}

/// The "Low latency" item and its check: only for a model with a game mode, which offers this switch.
pub fn low_latency_item(s: &Snapshot) -> Option<(&'static str, bool)> {
    types::has_feature(&s.features, s.caps.game_mode_id())
        .then_some(("widget_low_latency", s.low_latency.unwrap_or(false)))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn buds(battery: [(u8, bool); 3], anc: &str) -> Snapshot {
        Snapshot {
            status: Status::On, name: "OnePlus Buds 4".into(), anc: Some(anc.into()),
            battery: battery.map(Some), ..Default::default()
        }
    }

    fn modes(json: serde_json::Value) -> AncModes { AncModes::of(Some(&json)) }

    #[test]
    fn battery_line_lists_left_case_right() {
        let tr = crate::translations("");
        let s = buds([(80, false), (75, false), (40, false)], "Off");
        assert_eq!(battery_line(&s, &tr), "L:80 C:40 R:75");
    }

    #[test]
    fn battery_line_skips_a_missing_bud_and_names_the_model_with_no_reading() {
        let tr = crate::translations("");
        let s = buds([(80, false), (75, false), (40, false)], "Off");
        let mut half = s.clone();
        half.battery = [Some((80, false)), None, None];
        assert_eq!(battery_line(&half, &tr), "L:80");
        let mut none = s.clone();
        none.battery = [None; 3];
        assert_eq!(battery_line(&none, &tr), "OnePlus Buds 4");
    }

    #[test]
    fn battery_line_is_the_model_while_disconnected() {
        let tr = crate::translations("");
        let mut s = buds([(80, false), (75, false), (40, false)], "Off");
        s.status = Status::Off;
        assert_eq!(battery_line(&s, &tr), "OnePlus Buds 4");
        s.name.clear();
        assert_eq!(battery_line(&s, &tr), "QuickBuds");
    }

    #[test]
    fn tooltip_has_the_name_and_the_battery_line() {
        let tr = crate::translations("");
        let s = buds([(80, false), (75, false), (40, false)], "Off");
        assert_eq!(tooltip(&s, &tr), "OnePlus Buds 4\nL 80 · R 75 · Case 40");
        let mut off = s.clone();
        off.status = Status::Off;
        assert_eq!(tooltip(&off, &tr), "QuickBuds");
    }

    #[test]
    fn mode_items_are_the_offered_modes_with_the_current_one_checked() {
        let m = modes(serde_json::json!({"ancModes": [
            {"mode": 1, "slot": 8}, {"mode": 3, "slot": 1}, {"mode": 8, "slot": 2}, {"mode": 4, "slot": 4}
        ]}));
        let items = mode_items(&m, Some("ANC-Medium"));
        let names: Vec<&str> = items.iter().map(|(n, _, _)| *n).collect();
        assert_eq!(names, ["Off", "ANC-Light", "ANC-Medium", "ANC-Deep"]);
        let checked: Vec<&str> = items.iter().filter(|(_, _, c)| *c).map(|(n, _, _)| *n).collect();
        assert_eq!(checked, ["ANC-Medium"]);
    }

    #[test]
    fn mode_items_cover_adaptive_and_transparency() {
        let m = modes(serde_json::json!({"ancModes": [{"mode": 1, "slot": 8}, {"mode": 2, "slot": 4}, {"mode": 10, "slot": 2}]}));
        let names: Vec<&str> = mode_items(&m, None).iter().map(|(n, _, _)| *n).collect();
        assert_eq!(names, ["Off", "Adaptive", "Transparency"]);
        assert!(mode_items(&m, None).iter().all(|(_, _, c)| !*c));
    }

    #[test]
    fn mode_items_are_empty_for_a_model_with_no_modes() {
        assert!(mode_items(&AncModes::default(), Some("Off")).is_empty());
    }

    #[test]
    fn low_latency_item_follows_the_game_mode_feature() {
        let mut s = buds([(80, false), (75, false), (40, false)], "Off");
        assert_eq!(low_latency_item(&s), Some(("widget_low_latency", false)));
        s.low_latency = Some(true);
        assert_eq!(low_latency_item(&s), Some(("widget_low_latency", true)));
        s.features = vec![(0x99, 1)];
        assert_eq!(low_latency_item(&s), None);
    }
}
