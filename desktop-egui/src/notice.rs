//! The two short hints under the page: a refused setting write, and an ANC write refused
//! because no bud is in an ear. Each one stays for about four seconds.

use crate::glass;
use crate::session::Snapshot;
use crate::types::FEATURE_HIRES;
use crate::t;
use buds_core::feature_sync::ToastKind;
use eframe::egui::{FontId, Pos2, Rect, Ui, Vec2};
use std::time::{Duration, Instant};

/// How long a hint stays on the window.
pub const SHOW: Duration = Duration::from_secs(4);

#[derive(Default)]
pub struct State {
    /// The notice last shown, and when it arrived.
    notice: Option<(u32, Instant)>,
    /// The count of refused ANC writes last shown, and when it changed.
    anc: Option<(u32, Instant)>,
}

impl State {
    /// Records a new failure notice, or a new refused ANC write, and the time it arrived.
    pub fn update(&mut self, s: &Snapshot, now: Instant) {
        if let Some(n) = &s.notice {
            if self.notice.map_or(true, |(seq, _)| seq != n.seq) {
                self.notice = Some((n.seq, now));
            }
        }
        if s.anc_refused > 0 && self.anc.map_or(true, |(count, _)| count != s.anc_refused) {
            self.anc = Some((s.anc_refused, now));
        }
    }

    /// The text to show now: a refused write first, else the ANC ear hint. None when both are old.
    pub fn text(&self, tr: &[String], s: &Snapshot, now: Instant) -> Option<String> {
        if let Some((seq, at)) = self.notice {
            if s.notice.is_some_and(|n| n.seq == seq) && now.saturating_duration_since(at) < SHOW {
                return Some(notice_text(tr, s));
            }
        }
        if let Some((count, at)) = self.anc {
            if count == s.anc_refused && now.saturating_duration_since(at) < SHOW {
                return Some(t(tr, "anc_need_ear").to_string());
            }
        }
        None
    }

    /// How long until the shown hint hides, for the repaint that hides it.
    pub fn repaint_after(&self, now: Instant) -> Option<Duration> {
        [self.notice, self.anc].into_iter().flatten()
            .map(|(_, at)| SHOW.saturating_sub(now.saturating_duration_since(at)))
            .filter(|d| *d > Duration::ZERO)
            .min()
    }
}

/// The text of a failure notice, with the setting's name (the phone's strings).
pub fn notice_text(tr: &[String], s: &Snapshot) -> String {
    let Some(n) = &s.notice else { return String::new() };
    let name = if n.key == FEATURE_HIRES {
        t(tr, "row_hires_title").to_string()
    } else if n.key == s.caps.game_mode_id() {
        t(tr, "row_game_title").to_string()
    } else {
        format!("0x{:02X}", n.key)
    };
    let key = match n.kind {
        ToastKind::RefusedMissing => "feature_refused_missing",
        ToastKind::RefusedNow => "feature_refused_now",
        ToastKind::NotChanged => "feature_not_changed",
        ToastKind::NoAnswer => "feature_no_answer",
    };
    t(tr, key).replace("%1$s", &name)
}

/// Draws the hint as a toast at the bottom of the content column.
pub fn draw(ui: &Ui, text: &str, content: Rect) {
    let font = FontId::proportional(14.0);
    let galley = ui.painter().layout(text.to_string(), font, glass::white(1.0), content.width() - 64.0);
    let size = galley.size() + Vec2::new(32.0, 20.0);
    let rect = Rect::from_center_size(Pos2::new(content.center().x, content.bottom() - size.y / 2.0 - 12.0), size);
    glass::glass_at(ui, rect, 18.0, 1.6);
    ui.painter().galley(rect.center() - galley.size() / 2.0, galley, glass::white(1.0));
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::session::Notice;
    use crate::translations;

    fn refuse(kind: ToastKind, key: u8) -> Snapshot {
        Snapshot { notice: Some(Notice { key, kind, seq: 1 }), ..Default::default() }
    }

    #[test]
    fn each_kind_has_its_own_text() {
        let tr = translations("");
        let cases = [
            (ToastKind::RefusedMissing, "High-quality audio: these earbuds do not have this setting"),
            (ToastKind::RefusedNow, "High-quality audio: the earbuds refuse it right now"),
            (ToastKind::NotChanged, "High-quality audio: the earbuds did not change it"),
            (ToastKind::NoAnswer, "High-quality audio: the earbuds did not answer"),
        ];
        for (kind, want) in cases {
            assert_eq!(notice_text(&tr, &refuse(kind, FEATURE_HIRES)), want, "{kind:?}");
        }
    }

    #[test]
    fn an_unknown_setting_shows_its_id() {
        let tr = translations("");
        assert_eq!(notice_text(&tr, &refuse(ToastKind::NoAnswer, 0x55)), "0x55: the earbuds did not answer");
    }

    #[test]
    fn a_notice_lasts_four_seconds() {
        let tr = translations("");
        let s = refuse(ToastKind::NoAnswer, FEATURE_HIRES);
        let t0 = Instant::now();
        let mut st = State::default();
        st.update(&s, t0);
        assert!(st.text(&tr, &s, t0).is_some());
        assert!(st.text(&tr, &s, t0 + Duration::from_millis(3999)).is_some());
        assert!(st.text(&tr, &s, t0 + SHOW).is_none());
    }

    #[test]
    fn a_second_notice_restarts_the_clock() {
        let tr = translations("");
        let first = refuse(ToastKind::NoAnswer, FEATURE_HIRES);
        let second = Snapshot { notice: Some(Notice { key: FEATURE_HIRES, kind: ToastKind::NoAnswer, seq: 2 }), ..Default::default() };
        let t0 = Instant::now();
        let mut st = State::default();
        st.update(&first, t0);
        st.update(&second, t0 + Duration::from_secs(3));
        assert!(st.text(&tr, &second, t0 + Duration::from_secs(6)).is_some());
        assert!(st.text(&tr, &second, t0 + Duration::from_secs(7)).is_none());
    }

    #[test]
    fn the_anc_hint_lasts_four_seconds() {
        let tr = translations("");
        let s = Snapshot { anc_refused: 1, ..Default::default() };
        let t0 = Instant::now();
        let mut st = State::default();
        st.update(&s, t0);
        assert_eq!(st.text(&tr, &s, t0).as_deref(), Some("Put an earbud in to change noise control"));
        assert!(st.text(&tr, &s, t0 + Duration::from_millis(3999)).is_some());
        assert!(st.text(&tr, &s, t0 + SHOW).is_none());
    }
}
