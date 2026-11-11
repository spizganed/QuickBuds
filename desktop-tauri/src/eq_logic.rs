//! The equalizer's pick rules, apart from the link (the phone's `Equalizer.kt`, PROTOCOL.md section 17). No I/O here.
//!  - A reply that names another preset than the last pick is ignored while the pick is under [`PICK_HOLD_MS`] old.
//!  - Quick picks queued behind a write: only the last one is sent. A save with changes is kept.

use crate::session::Cmd;
use crate::types::Preset;

/// A pick holds the selection this long against replies of earlier picks.
pub const PICK_HOLD_MS: u64 = 4000;

/// The preset last picked, and when (ms).
#[derive(Default)]
pub struct PickHold { picked: Option<u8>, at: u64 }

impl PickHold {
    pub fn pick(&mut self, id: u8, now: u64) { self.picked = Some(id); self.at = now; }

    /// The buds report `id` as current: true when it may move the selection. A matching or late reply ends the hold.
    pub fn accept(&mut self, id: u8, now: u64) -> bool {
        if self.picked.is_some_and(|p| p != id) && now.saturating_sub(self.at) < PICK_HOLD_MS { return false; }
        self.picked = None;
        true
    }
}

/// A preset selection only: a built-in, or a custom preset saved unchanged.
fn is_pick(c: &Cmd, custom: &[Preset]) -> bool {
    match c {
        Cmd::EqBuiltIn(_) => true,
        Cmd::EqSave(p) => custom.contains(p),
        _ => false,
    }
}

/// For each queued command: true when it is sent. Every pick before the last pick is dropped.
pub fn sent(cmds: &[Cmd], custom: &[Preset]) -> Vec<bool> {
    let picks: Vec<bool> = cmds.iter().map(|c| is_pick(c, custom)).collect();
    let last = picks.iter().rposition(|&p| p);
    picks.iter().enumerate().map(|(i, &p)| !p || Some(i) == last).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_pick_holds_against_older_replies_for_4_s() {
        let mut h = PickHold::default();
        h.pick(1, 0);
        assert!(!h.accept(0, 1000));
        assert!(h.accept(0, PICK_HOLD_MS + 1));
        // The hold ended: any later reply counts.
        assert!(h.accept(2, PICK_HOLD_MS + 2));
    }

    #[test]
    fn the_matching_reply_ends_the_hold() {
        let mut h = PickHold::default();
        h.pick(1, 0);
        assert!(h.accept(1, 100));
        assert!(h.accept(0, 200));
    }

    #[test]
    fn only_the_last_pick_is_sent_and_a_changed_save_is_kept() {
        let p = Preset { id: 4, name: "A".into(), freqs: vec![], gains: vec![], selected: false, tag: [0xFA, 6] };
        let changed = Preset { name: "B".into(), ..p.clone() };
        let cmds = [Cmd::EqBuiltIn(1), Cmd::EqSave(changed), Cmd::EqBuiltIn(2), Cmd::EqSave(p.clone())];
        assert_eq!(sent(&cmds, &[p]), [false, true, false, true]);
    }
}
