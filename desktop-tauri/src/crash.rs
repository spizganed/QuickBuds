//! Crash records. The app writes each log line to `log.txt` while it runs. A panic writes its message to
//! `crash.txt`; on Linux a fatal signal (SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT) writes its name there too.
//! At the next start a non-empty `crash.txt` and the old `log.txt` become `crash-<time>.txt`, and the main
//! window offers to send it. All files are in the settings folder. Why: the log lives in memory, and a crash
//! lost it (report #228).

use std::fs::{File, OpenOptions};
use std::io::Write;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};

/// `log.txt` starts again when it grows past this size. The Worker keeps the last 1 000 000 characters.
const LOG_LIMIT: u64 = 4_000_000;

/// The record keeps the end of the old log only: the Worker keeps 1 000 000 characters, and the dialog shows it all.
const RECORD_LOG: usize = 500_000;

static LIVE: Mutex<Option<File>> = Mutex::new(None);
static LIVE_BYTES: AtomicU64 = AtomicU64::new(0);
static CRASH: OnceLock<File> = OnceLock::new();
/// The record of the last run's crash: the text and the path of its file. The main window takes it once.
static PENDING: Mutex<Option<(String, String)>> = Mutex::new(None);

fn dir() -> Option<PathBuf> { crate::settings_path().and_then(|p| p.parent().map(PathBuf::from)) }

/// Reads the last run's crash, starts the new `log.txt` and installs the handlers. Call once in `main`, after the single-instance check.
pub fn start() {
    let Some(dir) = dir() else { return };
    let _ = std::fs::create_dir_all(&dir);
    let crash = dir.join("crash.txt");
    let log = dir.join("log.txt");
    let found = std::fs::read_to_string(&crash).unwrap_or_default();
    if !found.trim().is_empty() {
        let old = std::fs::read_to_string(&log).unwrap_or_default();
        let text = crate::report::scrub(format!("{}\n{}", found.trim_end(), tail(&old, RECORD_LOG)));
        let [y, mo, d, h, mi, s, _] = crate::local_now();
        let saved = dir.join(format!("crash-{y:04}{mo:02}{d:02}-{h:02}{mi:02}{s:02}.txt"));
        let _ = std::fs::write(&saved, &text);
        *PENDING.lock().unwrap() = Some((text, saved.display().to_string()));
    }
    let _ = std::fs::rename(&log, dir.join("log-previous.txt"));
    if let Ok(f) = OpenOptions::new().create(true).write(true).truncate(true).open(&crash) { let _ = CRASH.set(f); }
    if let Ok(f) = OpenOptions::new().create(true).write(true).truncate(true).open(&log) {
        *LIVE.lock().unwrap() = Some(f);
        // The header asks Windows through `reg` and `cmd`: off the start path.
        std::thread::spawn(|| line(&crate::report::app_header()));
    }
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        if let Some(mut f) = CRASH.get() {
            let thread = std::thread::current();
            let _ = writeln!(f, "QuickBuds {} crash: panic in thread {}: {info}", env!("CARGO_PKG_VERSION"), thread.name().unwrap_or("?"));
        }
        previous(info);
    }));
    #[cfg(target_os = "linux")]
    signals::install();
}

/// The last [max] bytes of [text] or fewer, from the start of a line.
fn tail(text: &str, max: usize) -> &str {
    if text.len() <= max { return text; }
    let mut cut = text.len() - max;
    while !text.is_char_boundary(cut) { cut += 1; }
    let rest = &text[cut..];
    rest.find('\n').map_or(rest, |i| &rest[i + 1..])
}

/// Appends one line to `log.txt`.
pub fn line(text: &str) {
    let mut live = LIVE.lock().unwrap();
    let Some(f) = live.as_mut() else { return };
    let n = text.len() as u64 + 1;
    if LIVE_BYTES.fetch_add(n, Ordering::Relaxed) + n > LOG_LIMIT {
        // The file has no append mode: the cut also moves the write position back to the start.
        use std::io::Seek;
        let _ = f.set_len(0);
        let _ = f.rewind();
        LIVE_BYTES.store(n, Ordering::Relaxed);
    }
    let _ = writeln!(f, "{text}");
}

/// A crash record waits: the start opens the main window even with `--tray`.
pub fn has_pending() -> bool { PENDING.lock().unwrap().is_some() }

/// The last run's crash, once: (text, path of the saved file).
pub fn take_pending() -> Option<(String, String)> { PENDING.lock().unwrap().take() }

#[cfg(target_os = "linux")]
mod signals {
    use std::os::fd::AsRawFd;
    use std::sync::atomic::{AtomicI32, Ordering};
    use std::sync::OnceLock;

    const SIGNALS: [(libc::c_int, &[u8]); 5] = [
        (libc::SIGSEGV, b"QuickBuds crash: signal SIGSEGV\n"),
        (libc::SIGBUS, b"QuickBuds crash: signal SIGBUS\n"),
        (libc::SIGILL, b"QuickBuds crash: signal SIGILL\n"),
        (libc::SIGFPE, b"QuickBuds crash: signal SIGFPE\n"),
        (libc::SIGABRT, b"QuickBuds crash: signal SIGABRT\n"),
    ];
    static FD: AtomicI32 = AtomicI32::new(-1);
    /// The handlers before ours: Rust's stack overflow check, or the default.
    static OLD: OnceLock<Vec<(libc::c_int, libc::sigaction)>> = OnceLock::new();

    pub fn install() {
        let Some(f) = super::CRASH.get() else { return };
        FD.store(f.as_raw_fd(), Ordering::Relaxed);
        let mut old = Vec::new();
        for (sig, _) in SIGNALS {
            // SAFETY: zeroed sigaction structs, filled before the call; the handler is async-signal-safe.
            unsafe {
                let mut sa: libc::sigaction = std::mem::zeroed();
                sa.sa_sigaction = on_signal as extern "C" fn(libc::c_int) as libc::sighandler_t;
                // The alternate stack: a stack overflow cannot run a handler on the full stack.
                sa.sa_flags = libc::SA_ONSTACK;
                libc::sigemptyset(&mut sa.sa_mask);
                let mut prev: libc::sigaction = std::mem::zeroed();
                if libc::sigaction(sig, &sa, &mut prev) == 0 { old.push((sig, prev)); }
            }
        }
        let _ = OLD.set(old);
    }

    /// Writes the signal name, puts the earlier handler back and returns. A fault runs again into that handler;
    /// `abort` raises SIGABRT again with the default action. Only `write` and `sigaction`: both are async-signal-safe.
    extern "C" fn on_signal(sig: libc::c_int) {
        let fd = FD.load(Ordering::Relaxed);
        if let Some((_, msg)) = SIGNALS.iter().find(|(s, _)| *s == sig) {
            // SAFETY: a valid fd and a static buffer.
            if fd >= 0 { unsafe { libc::write(fd, msg.as_ptr().cast(), msg.len()); } }
        }
        if let Some((_, prev)) = OLD.get().and_then(|v| v.iter().find(|(s, _)| *s == sig)) {
            // SAFETY: the action saved by `install`.
            unsafe { libc::sigaction(sig, prev, std::ptr::null_mut()); }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::tail;

    #[test]
    fn tail_keeps_whole_lines_from_the_end() {
        assert_eq!(tail("abc\ndef\n", 100), "abc\ndef\n");
        assert_eq!(tail("abc\ndef\nghi\n", 6), "ghi\n");
        // The cut lands inside "é": it moves to the next character, then to the next line.
        assert_eq!(tail("ééé\nxy\n", 5), "xy\n");
    }
}
