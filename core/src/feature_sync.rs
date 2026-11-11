//! The sync engine for one setting write (docs/FEATURE-SYNC.md). Same names and rules as
//! `FeatureSync.kt`. No clock, no threads, no I/O: the caller passes `now` (ms).
//! One operation is active. The others wait in a queue, in tap order.

use std::collections::{HashMap, HashSet, VecDeque};

/// The wait for the ack of one write.
pub const ACK_MS: u64 = 3000;
/// The wait for the reply or the push after the last ack.
pub const WAIT_MS: u64 = 3000;
/// The wait for the first report after a restart.
pub const RESTART_MS: u64 = 15000;

/// How the buds confirm a write.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Confirm {
    /// The reply to our own status read decides.
    Read,
    /// The first real push for the key decides.
    Push,
    /// The ack decides: the buds cannot report the value.
    Ack,
}

/// What a failure toast says.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum ToastKind {
    RefusedMissing,
    RefusedNow,
    NotChanged,
    NoAnswer,
}

/// One user action: the writes, and how the buds confirm them.
#[derive(Clone, Debug)]
pub struct Operation {
    /// The setting the user touched: it shows "applying" and its value is checked.
    pub key: u8,
    /// (feature id, value), sent one after the other.
    pub writes: Vec<(u8, u8)>,
    pub confirm: Confirm,
    /// The write restarts the link: wait for the first report after the reconnect.
    pub restarts: bool,
}

/// What the engine asks the caller to do, in order.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Out {
    Busy { key: u8, on: bool },
    Send { id: u8, value: u8, seq: u32 },
    Read { seq: u32 },
    Changed { id: u8, value: u8 },
    Toast { key: u8, kind: ToastKind },
}

impl Out {
    /// The scenario file's token.
    pub fn token(&self) -> String {
        match *self {
            Out::Busy { key, on } => format!("busy:{key}:{}", if on { "on" } else { "off" }),
            Out::Send { id, value, seq } => format!("send:{id}:{value}:s{seq}"),
            Out::Read { seq } => format!("read:s{seq}"),
            Out::Changed { id, value } => format!("changed:{id}:{value}"),
            Out::Toast { key, kind } => format!("toast:{key}:{}", kind.name()),
        }
    }
}

impl ToastKind {
    pub fn name(&self) -> &'static str {
        match self {
            ToastKind::RefusedMissing => "refused_missing",
            ToastKind::RefusedNow => "refused_now",
            ToastKind::NotChanged => "not_changed",
            ToastKind::NoAnswer => "no_answer",
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Phase {
    Ack,
    Reply,
    Push,
    Restart,
}

struct Active {
    op: Operation,
    write: usize,
    phase: Phase,
    seq: u32,
    deadline: u64,
    read_seq: u32,
    /// Writes of this operation that got status 0.
    acked: u32,
    reconnected: bool,
    /// The link went down after a restarts write.
    dropped: bool,
}

impl Active {
    fn new(op: Operation) -> Self {
        Active { op, write: 0, phase: Phase::Ack, seq: 0, deadline: 0, read_seq: 0, acked: 0, reconnected: false, dropped: false }
    }
}

pub struct FeatureSync {
    values: HashMap<u8, u8>,
    busy_keys: HashSet<u8>,
    queue: VecDeque<Operation>,
    active: Option<Active>,
    /// One counter for every send and every read.
    next_seq: u32,
}

impl Default for FeatureSync {
    fn default() -> Self {
        FeatureSync::new()
    }
}

impl FeatureSync {
    pub fn new() -> Self {
        FeatureSync { values: HashMap::new(), busy_keys: HashSet::new(), queue: VecDeque::new(), active: None, next_seq: 1 }
    }

    pub fn init(&mut self, values: impl IntoIterator<Item = (u8, u8)>) {
        self.values.extend(values);
    }

    pub fn tap(&mut self, op: Operation, now: u64) -> Vec<Out> {
        if self.busy_keys.contains(&op.key) {
            return Vec::new();
        }
        self.busy_keys.insert(op.key);
        let mut out = vec![Out::Busy { key: op.key, on: true }];
        if self.active.is_none() {
            self.start(op, now, &mut out);
        } else {
            self.queue.push_back(op);
        }
        out
    }

    pub fn ack(&mut self, seq: u32, status: u8, now: u64) -> Vec<Out> {
        let mut out = Vec::new();
        let Some(mut a) = self.active.take() else { return out };
        if a.phase != Phase::Ack || seq != a.seq {
            self.active = Some(a);
            return out;
        }

        if status != 0 {
            out.push(Out::Toast { key: a.op.key, kind: if status == 1 { ToastKind::RefusedMissing } else { ToastKind::RefusedNow } });
            self.stop(&a, &mut out);
            // An earlier write went through: one read re-syncs the controls.
            if a.acked > 0 {
                out.push(Out::Read { seq: self.next() });
            }
            self.start_next(now, &mut out);
            return out;
        }

        a.acked += 1;
        a.write += 1;
        if a.write < a.op.writes.len() {
            self.send_write(&mut a, now, &mut out);
            self.active = Some(a);
            return out;
        }

        if a.op.restarts {
            a.phase = Phase::Restart;
            a.deadline = now + RESTART_MS;
            self.active = Some(a);
            return out;
        }
        match a.op.confirm {
            Confirm::Read => {
                a.read_seq = self.next();
                a.phase = Phase::Reply;
                a.deadline = now + WAIT_MS;
                out.push(Out::Read { seq: a.read_seq });
                self.active = Some(a);
            }
            Confirm::Push => {
                // The buds can report the new value BEFORE the ack (a fast push: 151 ms in report #68). That report
                // already confirms the write, and a second push never comes. Wait only for the old value.
                let written = a.op.writes.iter().rev().find(|(id, _)| *id == a.op.key).map(|(_, v)| *v);
                if written.is_some() && self.values.get(&a.op.key).copied() == written {
                    self.stop(&a, &mut out);
                    self.start_next(now, &mut out);
                } else {
                    a.phase = Phase::Push;
                    a.deadline = now + WAIT_MS;
                    self.active = Some(a);
                }
            }
            Confirm::Ack => {
                self.apply_writes(&a, &mut out);
                self.stop(&a, &mut out);
                self.start_next(now, &mut out);
            }
        }
        out
    }

    /// `push`: the buds sent it on their own (an event). A read reply that is not ours is NOT a push:
    /// it only updates values.
    pub fn report(&mut self, values: &[(u8, u8)], seq: Option<u32>, now: u64, push: bool) -> Vec<Out> {
        let mut out = Vec::new();
        let mut sorted = values.to_vec();
        sorted.sort_by_key(|v| v.0);
        for (id, value) in sorted {
            if self.values.get(&id) != Some(&value) {
                self.values.insert(id, value);
                out.push(Out::Changed { id, value });
            }
        }

        let Some(a) = self.active.take() else { return out };
        let confirms = match a.phase {
            Phase::Reply => seq.is_some_and(|s| s == a.read_seq),
            Phase::Push => push && values.iter().any(|v| v.0 == a.op.key),
            Phase::Restart => a.reconnected,
            Phase::Ack => false,
        };
        if !confirms {
            self.active = Some(a);
            return out;
        }

        let written = a.op.writes.iter().rev().find(|w| w.0 == a.op.key).map(|w| w.1);
        if written.is_some_and(|w| self.values.get(&a.op.key).copied() != Some(w)) {
            out.push(Out::Toast { key: a.op.key, kind: ToastKind::NotChanged });
        }
        self.stop(&a, &mut out);
        self.start_next(now, &mut out);
        out
    }

    pub fn tick(&mut self, now: u64) -> Vec<Out> {
        let mut out = Vec::new();
        let Some(mut a) = self.active.take() else { return out };
        if now < a.deadline {
            self.active = Some(a);
            return out;
        }
        // A restarts write, and the link never dropped: these buds did not restart (realme Buds T500 Pro, report 128).
        // One read decides.
        if a.phase == Phase::Restart && !a.dropped {
            a.read_seq = self.next();
            a.phase = Phase::Reply;
            a.deadline = now + WAIT_MS;
            out.push(Out::Read { seq: a.read_seq });
            self.active = Some(a);
            return out;
        }
        out.push(Out::Toast { key: a.op.key, kind: ToastKind::NoAnswer });
        self.stop(&a, &mut out);
        self.start_next(now, &mut out);
        out
    }

    pub fn disconnect(&mut self, _now: u64) -> Vec<Out> {
        let mut out = Vec::new();
        if let Some(mut a) = self.active.take() {
            // A restarts write that was acked keeps waiting across the drop.
            if a.op.restarts && a.phase == Phase::Restart {
                a.dropped = true;
                self.active = Some(a);
            } else {
                out.push(Out::Toast { key: a.op.key, kind: ToastKind::NoAnswer });
                self.stop(&a, &mut out);
            }
        }
        // Queued operations sent nothing: busy off, no toast.
        while let Some(op) = self.queue.pop_front() {
            self.busy_keys.remove(&op.key);
            out.push(Out::Busy { key: op.key, on: false });
        }
        out
    }

    pub fn reconnect(&mut self, _now: u64) -> Vec<Out> {
        if let Some(a) = self.active.as_mut() {
            a.reconnected = true;
        }
        Vec::new()
    }

    pub fn value(&self, id: u8) -> Option<u8> {
        self.values.get(&id).copied()
    }

    pub fn busy(&self, key: u8) -> bool {
        self.busy_keys.contains(&key)
    }

    /// An operation is running or waiting: the caller's failure-detector tick is needed.
    pub fn active(&self) -> bool {
        self.active.is_some()
    }

    fn next(&mut self) -> u32 {
        let seq = self.next_seq;
        self.next_seq += 1;
        seq
    }

    fn start(&mut self, op: Operation, now: u64, out: &mut Vec<Out>) {
        let mut a = Active::new(op);
        self.send_write(&mut a, now, out);
        self.active = Some(a);
    }

    fn send_write(&mut self, a: &mut Active, now: u64, out: &mut Vec<Out>) {
        let (id, value) = a.op.writes[a.write];
        a.seq = self.next();
        a.phase = Phase::Ack;
        a.deadline = now + ACK_MS;
        out.push(Out::Send { id, value, seq: a.seq });
    }

    /// Set the written values and report each real change, in ascending id order.
    fn apply_writes(&mut self, a: &Active, out: &mut Vec<Out>) {
        let writes: std::collections::BTreeMap<u8, u8> = a.op.writes.iter().copied().collect();
        for (id, value) in writes {
            if self.values.get(&id) != Some(&value) {
                self.values.insert(id, value);
                out.push(Out::Changed { id, value });
            }
        }
    }

    /// End the active operation: busy off.
    fn stop(&mut self, a: &Active, out: &mut Vec<Out>) {
        self.busy_keys.remove(&a.op.key);
        out.push(Out::Busy { key: a.op.key, on: false });
    }

    fn start_next(&mut self, now: u64, out: &mut Vec<Out>) {
        if let Some(op) = self.queue.pop_front() {
            self.start(op, now, out);
        }
    }
}
