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
    /// A `0x0403` switch (Earbud settings), then the status re-read.
    Feature(u8, bool),
    /// The locator tone on both buds.
    Find(bool),
    /// The model list's pick changed (`settings.json` "model_manual"): the model is looked up again.
    ModelPicked,
    /// Earbud settings opened: the reads the phone's screen does then (alert volume, tap sensitivity).
    EarbudReads,
    AlertVolume(u8),
    TapLevel(u8),
    GameSoundType(u8),
    HeadMotion(u8),
    /// `0x0405` start / stop.
    FitTest(bool),
    /// Personalized ANC: `0x011A` (is a result stored?).
    PncQuery,
    /// `0x0412 <action>`; using the stored result re-reads the status after it.
    Pnc(u8),
    /// `0x010D`, after a Personalized ANC result applied (the buds switch `0C` on themselves).
    StatusRead,
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
    /// The model in use: the manual pick, else [detected](Self::detected).
    pub model: Option<&'static serde_json::Value>,
    /// What the product id (else the name) says.
    pub detected: Option<&'static serde_json::Value>,
    /// The model was picked by hand: its `models.json` flags count as features (`Capabilities.offered`).
    pub manual: bool,
    pub caps: Caps,
    pub firmware: Option<String>,
    pub eq_current: Option<u8>,
    pub eq_custom: Vec<Preset>,
    pub bass_on: Option<bool>,
    pub bass_level: Option<i8>,
    /// `0x810D`: every feature id the buds listed, with its value.
    pub features: Vec<(u8, u8)>,
    pub alert_volume: Option<u8>,
    /// (level, the buds' default)
    pub tap_level: Option<(u8, u8)>,
    /// (selected, offered types) from `0x812B`.
    pub game_sound: Option<(u8, Vec<u8>)>,
    pub head_motion: Option<u8>,
    /// The last fit test result, and a count that changes with each one.
    pub fit: (u8, u8, u32),
    /// The last Personalized ANC event (1 stored, 2 ack, 3 result), its value, and a count that changes with each.
    pub pnc: (u8, u8, u32),
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

/// The manual pick, else the detected model; the ANC modes follow it.
fn pick_model(s: &mut Snapshot) {
    let manual = manual_model();
    s.manual = manual.is_some();
    s.model = manual.or(s.detected);
    s.modes = AncModes::of(s.model);
}

/// The model picked by hand in the model list, if any.
fn manual_model() -> Option<&'static serde_json::Value> {
    let id = crate::load_settings()["model_manual"].as_str()?.to_string();
    models().iter().find(|m| m["id"].as_str() == Some(&id))
}

/// Addresses that answered as buds before (`settings.json` "buds"), for systems that do not list the vendor service.
fn remembered() -> Vec<u64> {
    crate::load_settings()["buds"].as_array().into_iter().flatten()
        .filter_map(|v| u64::from_str_radix(v.as_str()?, 16).ok()).collect()
}

fn remember(addr: u64) {
    let mut list = remembered();
    if addr == 0 || list.contains(&addr) { return; }
    list.push(addr);
    crate::save_setting("buds", list.iter().map(|a| format!("{a:012X}")).collect::<Vec<_>>().into());
}

fn run(rx: Receiver<Cmd>, emit: Emit) {
    let mut paused = false;
    let mut manual = false;
    loop {
        if !paused {
            // Auto-connect follows audio: only buds the system has a link to. Buds are found by the vendor's
            // control service (`BudsDevice.find`), or as the device that answered before; a known model name
            // is the last resort. A user Connect also tries any other connected device.
            let known = remembered();
            let rank = |d: &bt::Device| if d.vendor || known.contains(&d.addr) { 0 } else if is_known_name(&d.name) { 1 } else { 2 };
            let mut devices: Vec<_> = bt::paired().into_iter().filter(|d| d.connected && (manual || rank(d) < 2)).collect();
            devices.sort_by_key(rank);
            for d in devices {
                emit(Snapshot { status: Status::Connecting, name: d.name.clone(), ..Default::default() });
                let Ok(link) = bt::connect(d.addr) else { continue };
                let mut c = Conn::new(link, d.name, &emit);
                c.addr = d.addr;
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
    /// 0 for the bridge.
    addr: u64,
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
        let mut s = Snapshot { status: Status::On, name, detected: model, ..Default::default() };
        pick_model(&mut s);
        Conn { link, addr: 0, framer: Framer::default(), seq: 0, level: None, s, emit }
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
                // Other buds than the manual pick was made for: back to Automatic (`ModelCatalog`).
                let all = crate::load_settings();
                if all["product_id"].as_str() != Some(&id) {
                    crate::save_setting("product_id", id.as_str().into());
                    if !all["model_manual"].is_null() { crate::save_setting("model_manual", serde_json::Value::Null); }
                }
                s.detected = find_model(Some(&id), Some(&s.name));
                pick_model(s);
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
            Event::AlertVolume(l) => s.alert_volume = Some(l),
            Event::TapLevel(l, d) => s.tap_level = Some((l, d)),
            Event::GameSound(t, all) => s.game_sound = Some((t, all)),
            Event::HeadMotion(t) => s.head_motion = Some(t),
            Event::FitResult(l, r) => s.fit = (l, r, s.fit.2 + 1),
            Event::PncStored(e) => s.pnc = (1, e as u8, s.pnc.2 + 1),
            Event::PncAck(st) => s.pnc = (2, st, s.pnc.2 + 1),
            Event::PncResult(r) => s.pnc = (3, r, s.pnc.2 + 1),
            Event::Features(f) => {
                let get = |id: u8| f.iter().find(|x| x.0 == id).map(|x| x.1 == 1);
                s.low_latency = get(s.caps.game_mode_id()).or(s.low_latency);
                s.bass_on = get(FEATURE_BASSWAVE).or(s.bass_on);
                for (id, v) in f {
                    match s.features.iter_mut().find(|x| x.0 == id) { Some(x) => x.1 = v, None => s.features.push((id, v)) }
                }
            }
        }
        true
    }

    fn serve(&mut self, rx: &Receiver<Cmd>) -> End {
        (self.emit)(self.s.clone());
        let init = self.init();
        // Buds that named their model are remembered, renamed or not.
        if init.is_ok() && self.s.model.is_some() { remember(self.addr); }
        match init.and_then(|_| self.run(rx)) {
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
                    // As `setFeatures`: the write, then the status read that shows what the buds kept.
                    // Power saving restarts the buds: the link drops and the idle rescan reconnects.
                    Cmd::Feature(id, on) => {
                        if let Some(x) = self.s.features.iter_mut().find(|x| x.0 == id) { x.1 = on as u8; }
                        (self.emit)(self.s.clone());
                        self.send(CMD_SET_FEATURE, None, &[id, on as u8])?;
                        self.pump(400)?;
                        self.send(CMD_QUERY_STATUS, Some(0x00), STATUS_QUERY)?;
                    }
                    Cmd::Find(on) => self.send(CMD_FIND_BUDS, None, &[on as u8])?,
                    Cmd::FitTest(on) => self.send(CMD_FIT_TEST, None, &[on as u8])?,
                    Cmd::PncQuery => self.send(CMD_QUERY_PERSONAL_NOISE, None, &[])?,
                    Cmd::Pnc(action) => {
                        self.send(CMD_PERSONAL_NOISE, None, &[action])?;
                        if action == 2 { self.pump(600)?; self.send(CMD_QUERY_STATUS, Some(0x00), STATUS_QUERY)?; }
                    }
                    Cmd::StatusRead => self.send(CMD_QUERY_STATUS, Some(0x00), STATUS_QUERY)?,
                    // As `writeThenRead`: shown at once, then read back.
                    Cmd::GameSoundType(t) => {
                        if let Some(g) = self.s.game_sound.as_mut() { g.0 = t; }
                        (self.emit)(self.s.clone());
                        self.send(CMD_GAME_SOUND, None, &[t, 1])?;
                        self.pump(400)?;
                        self.send(CMD_QUERY_GAME_SOUND, None, &[])?;
                    }
                    Cmd::HeadMotion(t) => {
                        self.s.head_motion = Some(t);
                        (self.emit)(self.s.clone());
                        self.send(CMD_SET_HEAD_MOTION, None, &[t])?;
                        self.pump(400)?;
                        self.send(CMD_QUERY_HEAD_MOTION, None, &[])?;
                    }
                    Cmd::ModelPicked => pick_model(&mut self.s),
                    Cmd::EarbudReads => for cmd in [CMD_QUERY_ALERT_VOLUME, CMD_QUERY_TAP_LEVEL, CMD_QUERY_GAME_SOUND, CMD_QUERY_HEAD_MOTION] {
                        if !self.s.caps.supports(cmd) { continue; }
                        self.send(cmd, None, &[])?;
                        self.pump(120)?;
                    },
                    // Sent on release only, so the buds play one prompt per change; the ack carries the level.
                    Cmd::AlertVolume(l) => {
                        self.s.alert_volume = Some(l);
                        self.send(CMD_SET_ALERT_VOLUME, None, &[l.clamp(1, 10)])?;
                    }
                    // Unverified on hardware: read back, so the slider shows what the buds kept.
                    Cmd::TapLevel(l) => {
                        if let Some(t) = self.s.tap_level.as_mut() { t.0 = l; }
                        self.send(CMD_SET_TAP_LEVEL, None, &[l.clamp(1, 5)])?;
                        self.pump(250)?;
                        self.send(CMD_QUERY_TAP_LEVEL, None, &[])?;
                    }
                    Cmd::Disconnect => return Ok(End::UserDisconnect),
                    Cmd::Connect => continue,
                }
                (self.emit)(self.s.clone());
            }
        }
    }
}
