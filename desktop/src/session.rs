//! The link to the buds on its own thread: finds them, runs the init sequence, applies pushes and
//! sends the UI's commands. Mirrors `BudsConnectionManager`: nothing is polled after connect.

use crate::bt;
use crate::protocol::*;
use std::sync::mpsc::{channel, Receiver, RecvTimeoutError, Sender};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

pub enum Cmd {
    Anc(String),
    LowLatency(bool),
    EqBuiltIn(u8),
    /// Select and save a custom preset (a band edit or rename is the same frame).
    EqSave(Preset),
    EqCreate(Preset),
    EqDelete(Preset),
    BassWave(bool),
    BassLevel(i8),
    Connect,
    Disconnect,
}

#[derive(Clone, Copy, Default, PartialEq)]
pub enum Status {
    #[default]
    Off,
    Connecting,
    On,
}

#[derive(Clone, Default)]
pub struct Snapshot {
    pub status: Status,
    pub name: String,
    /// Left, right, case: (level, charging).
    pub battery: [Option<(u8, bool)>; 3],
    /// Left, right, case: raw wear status (§8), 0 = unknown.
    pub wear: [u8; 3],
    pub anc: Option<String>,
    pub modes: AncModes,
    pub low_latency: Option<bool>,
    pub model: Option<&'static serde_json::Value>,
    pub caps: Caps,
    pub firmware: Option<String>,
    pub eq_current: Option<u8>,
    pub eq_custom: Vec<Preset>,
    pub bass_on: Option<bool>,
    pub bass_level: Option<i8>,
}

type Emit = Box<dyn Fn(Snapshot) + Send>;

/// One packet log line (Dev tools).
pub struct Line {
    /// Since the app started.
    pub at: Duration,
    /// "TX", "RX" or "DISCARDED RX".
    pub dir: &'static str,
    pub bytes: Vec<u8>,
    /// The decoded description; None = a packet nothing here names (amber in Human).
    pub human: Option<String>,
}

// ponytail: unbounded; a cap if a days-long session ever shows in memory.
pub static LOG: Mutex<Vec<Line>> = Mutex::new(Vec::new());

pub fn app_start() -> Instant { *START.get_or_init(Instant::now) }
static START: OnceLock<Instant> = OnceLock::new();

fn log(dir: &'static str, bytes: Vec<u8>, human: Option<String>) {
    LOG.lock().unwrap().push(Line { at: app_start().elapsed(), dir, bytes, human });
}

pub fn spawn(emit: impl Fn(Snapshot) + Send + 'static) -> Sender<Cmd> {
    let (tx, rx) = channel();
    std::thread::spawn(move || run(rx, Box::new(emit)));
    tx
}

enum End { Lost, UserDisconnect, Quit }

fn run(rx: Receiver<Cmd>, emit: Emit) {
    let mut paused = false;
    let mut manual = false;
    loop {
        if !paused {
            // Auto-connect follows audio: only buds Windows has a link to. A known model name first;
            // a user Connect also tries any other connected device.
            let mut devices: Vec<_> = bt::paired().into_iter()
                .filter(|d| d.connected && (manual || is_known_name(&d.name))).collect();
            devices.sort_by_key(|d| !is_known_name(&d.name));
            for d in devices {
                emit(Snapshot { status: Status::Connecting, name: d.name.clone(), ..Default::default() });
                let Ok(link) = bt::connect(d.addr) else { continue };
                let mut c = Conn::new(link, d.name, &emit);
                match c.serve(&rx) {
                    End::Lost => {}
                    End::UserDisconnect => paused = true,
                    End::Quit => return,
                }
                break;
            }
            emit(Snapshot::default());
        }
        manual = false;
        // ponytail: 5 s rescan while idle; a Windows device-change notification if this ever costs.
        match rx.recv_timeout(Duration::from_secs(5)) {
            Ok(Cmd::Connect) => { paused = false; manual = true; }
            Ok(Cmd::Disconnect) => paused = true,
            Ok(_) | Err(RecvTimeoutError::Timeout) => {}
            Err(RecvTimeoutError::Disconnected) => return,
        }
    }
}

struct Conn<'a> {
    link: bt::Link,
    framer: Framer,
    seq: u8,
    /// The last ANC level sent, to name a report that says only "noise cancelling".
    level: Option<String>,
    s: Snapshot,
    emit: &'a Emit,
}

impl<'a> Conn<'a> {
    fn new(link: bt::Link, name: String, emit: &'a Emit) -> Self {
        let model = find_model(None, Some(&name));
        let s = Snapshot { status: Status::On, name, modes: AncModes::of(model), model, ..Default::default() };
        Conn { link, framer: Framer::default(), seq: 0, level: None, s, emit }
    }

    fn send(&mut self, cmd: u16, seq: Option<u8>, payload: &[u8]) -> Result<(), String> {
        let seq = seq.unwrap_or_else(|| { self.seq = self.seq % 0xFE + 1; self.seq });
        let p = build_packet(cmd, seq, payload);
        log("TX", p.clone(), cmd_name(cmd).map(String::from));
        self.link.write(&p)
    }

    /// Reads and applies packets for `ms`.
    fn pump(&mut self, ms: u64) -> Result<(), String> {
        let end = Instant::now() + Duration::from_millis(ms);
        let mut buf = [0u8; 1024];
        loop {
            let n = self.link.read(&mut buf)?;
            let mut changed = false;
            for p in self.framer.push(&buf[..n]) {
                let e = decode(&p);
                log("RX", p, e.as_ref().map(describe));
                if let Some(e) = e { changed |= self.apply(e); }
            }
            if !self.framer.discarded.is_empty() { log("DISCARDED RX", std::mem::take(&mut self.framer.discarded), None); }
            if changed { (self.emit)(self.s.clone()); }
            if Instant::now() >= end { return Ok(()); }
        }
    }

    fn apply(&mut self, e: Event) -> bool {
        let s = &mut self.s;
        match e {
            Event::Caps(c) => s.caps = c,
            Event::ProductId(id) => {
                s.model = find_model(Some(&id), Some(&s.name));
                s.modes = AncModes::of(s.model);
                // The bridge (or an unknown device name) shows the model's name instead.
                if let Some(n) = s.model.and_then(|m| m["name"].as_str()) {
                    if !is_known_name(&s.name) { s.name = n.to_string(); }
                }
            }
            Event::Firmware(f) => s.firmware = Some(f),
            Event::EqCurrent(id) => s.eq_current = Some(id),
            Event::EqCustom(list) => s.eq_custom = list,
            Event::BassLevel(l) => s.bass_level = Some(l),
            Event::Battery(v) => for (i, level, charging) in v {
                if (1..=3).contains(&i) { s.battery[i as usize - 1] = Some((level, charging)); }
            },
            Event::Wear(v) => for (i, st) in v {
                if (1..=3).contains(&i) { s.wear[i as usize - 1] = st; }
            },
            Event::AncRaw(raw) => match s.modes.mode_for_raw(raw, self.level.as_deref()) {
                Some(m) => s.anc = Some(m),
                None => return false,
            },
            Event::GameMode(on) => s.low_latency = Some(on),
            Event::Features(f) => {
                let get = |id: u8| f.iter().find(|x| x.0 == id).map(|x| x.1 == 1);
                s.low_latency = get(s.caps.game_mode_id()).or(s.low_latency);
                s.bass_on = get(FEATURE_BASSWAVE).or(s.bass_on);
            }
        }
        true
    }

    fn serve(&mut self, rx: &Receiver<Cmd>) -> End {
        (self.emit)(self.s.clone());
        match self.init().and_then(|_| self.run(rx)) {
            Ok(end) => end,
            Err(_) => End::Lost,
        }
    }

    /// PROTOCOL.md "Init sequence": 300 ms before the first frame, then 200 ms apart; reads only if listed.
    fn init(&mut self) -> Result<(), String> {
        self.pump(300)?;
        for cmd in [CMD_HANDSHAKE, CMD_QUERY_PRODUCT_ID, CMD_QUERY_BROADCAST] {
            self.send(cmd, None, &[])?;
            self.pump(200)?;
        }
        let queries: [(u16, Option<u8>, Vec<u8>); 6] = [
            (CMD_REGISTER_NOTIFY, None, register_payload(&self.s.caps)),
            (CMD_QUERY_STATUS, Some(0x00), STATUS_QUERY.to_vec()),
            (CMD_QUERY_ANC, None, vec![1, 1]),
            (CMD_QUERY_BATTERY, Some(0xF0), vec![]),
            (CMD_QUERY_WEARING, Some(0xF2), vec![]),
            (CMD_QUERY_FIRMWARE, None, vec![]),
        ];
        for (cmd, seq, payload) in queries {
            if !self.s.caps.supports(cmd) { continue; }
            self.send(cmd, seq, &payload)?;
            self.pump(200)?;
        }
        self.eq_reads()
    }

    /// The EQ reads these buds list, in order (`sendThenRead`); after a write too, since only the buds know
    /// the ids after a create or delete.
    fn eq_reads(&mut self) -> Result<(), String> {
        for cmd in [CMD_QUERY_EQ, CMD_QUERY_EQ_ALL, CMD_QUERY_BASSWAVE] {
            if !self.s.caps.supports(cmd) { continue; }
            self.send(cmd, None, &[])?;
            self.pump(120)?;
        }
        Ok(())
    }

    /// A write, then the EQ re-read.
    fn eq_write(&mut self, cmd: u16, payload: &[u8]) -> Result<(), String> {
        (self.emit)(self.s.clone());
        self.send(cmd, None, payload)?;
        self.pump(250)?;
        self.eq_reads()
    }

    fn run(&mut self, rx: &Receiver<Cmd>) -> Result<End, String> {
        loop {
            self.pump(0)?;
            loop {
                let cmd = match rx.try_recv() {
                    Ok(c) => c,
                    Err(std::sync::mpsc::TryRecvError::Empty) => break,
                    Err(_) => return Ok(End::Quit),
                };
                match cmd {
                    Cmd::Anc(mode) => {
                        // A mode this model does not list is never sent: its bit would be another mode's.
                        let Some(bit) = self.s.modes.bit(&mode) else { continue };
                        if LEVELS.contains(&mode.as_str()) { self.level = Some(mode.clone()); }
                        self.send(CMD_SET_ANC, None, &anc_payload(bit))?;
                        self.s.anc = Some(mode);
                    }
                    Cmd::LowLatency(on) => {
                        self.send(CMD_SET_FEATURE, None, &[self.s.caps.game_mode_id(), on as u8])?;
                        self.s.low_latency = Some(on);
                    }
                    // Each EQ write updates the state first, so the replies before the new values
                    // do not snap the UI back.
                    Cmd::EqBuiltIn(id) => {
                        self.s.eq_current = Some(id);
                        self.eq_write(CMD_SET_EQ, &[id])?;
                    }
                    Cmd::EqSave(p) => {
                        self.s.eq_current = Some(p.id);
                        if let Some(x) = self.s.eq_custom.iter_mut().find(|x| x.id == p.id) { *x = p.clone(); }
                        self.eq_write(CMD_SAVE_CUSTOM_EQ, &p.encode(EQ_SAVE))?;
                    }
                    // No local update: the buds assign and renumber ids.
                    Cmd::EqCreate(p) => self.eq_write(CMD_SAVE_CUSTOM_EQ, &p.encode(EQ_CREATE))?,
                    Cmd::EqDelete(p) => self.eq_write(CMD_SAVE_CUSTOM_EQ, &p.encode(EQ_DELETE))?,
                    Cmd::BassWave(on) => {
                        self.s.bass_on = Some(on);
                        self.send(CMD_SET_FEATURE, None, &[FEATURE_BASSWAVE, on as u8])?;
                    }
                    Cmd::BassLevel(l) => {
                        self.s.bass_level = Some(l);
                        self.eq_write(CMD_SET_BASSWAVE, &[0xFB, 0x05, l as u8])?;
                    }
                    Cmd::Disconnect => return Ok(End::UserDisconnect),
                    Cmd::Connect => continue,
                }
                (self.emit)(self.s.clone());
            }
        }
    }
}
