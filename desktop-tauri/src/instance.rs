//! One copy of QuickBuds for each user. The close button only hides the window, so a second start found
//! the RFCOMM link held by the first copy and stayed on "Connecting" (report 190). The first copy
//! listens; a second start asks it to show its window, then exits.
//! Linux: a socket in `XDG_RUNTIME_DIR`, which is per user. Windows, or Linux without that directory: a fixed
//! local port, which is machine-wide. TODO(Windows): a named mutex in the session namespace, so each user gets a copy.

use std::io::{Read, Write};
use std::time::Duration;

const TCP_ADDR: &str = "127.0.0.1:47391";
const HELLO: &[u8] = b"quickbuds-show\n";
const TIMEOUT: Duration = Duration::from_secs(1);

pub enum Listener {
    Tcp(std::net::TcpListener),
    #[cfg(target_os = "linux")]
    Unix(std::os::unix::net::UnixListener),
}

#[cfg(target_os = "linux")]
fn socket_path() -> Option<std::path::PathBuf> {
    std::env::var_os("XDG_RUNTIME_DIR").filter(|d| !d.is_empty()).map(|d| std::path::Path::new(&d).join("quickbuds.sock"))
}

/// Binds the socket at `path`. A socket file that no copy answers is left over from a copy that ended: it is replaced.
#[cfg(target_os = "linux")]
fn bind_unix(path: &std::path::Path) -> Option<std::os::unix::net::UnixListener> {
    use std::os::unix::net::{UnixListener, UnixStream};
    match UnixListener::bind(path) {
        Ok(l) => Some(l),
        Err(e) if e.kind() == std::io::ErrorKind::AddrInUse && UnixStream::connect(path).is_err() => {
            let _ = std::fs::remove_file(path);
            UnixListener::bind(path).ok()
        }
        Err(_) => None,
    }
}

fn bind() -> Option<Listener> {
    #[cfg(target_os = "linux")]
    if let Some(p) = socket_path() { return bind_unix(&p).map(Listener::Unix); }
    std::net::TcpListener::bind(TCP_ADDR).ok().map(Listener::Tcp)
}

/// Sends the hello on `c`. True when the first copy answers `ok`.
fn ask<S: Read + Write>(mut c: S) -> bool {
    let mut reply = [0u8; 2];
    c.write_all(HELLO).is_ok() && c.read_exact(&mut reply).is_ok() && &reply == b"ok"
}

/// Reads one hello on `c` and answers `ok`. False for anything else.
fn answer<S: Read + Write>(mut c: S) -> bool {
    let mut hello = vec![0u8; HELLO.len()];
    if c.read_exact(&mut hello).is_err() || hello != HELLO { return false; }
    let _ = c.write_all(b"ok");
    true
}

/// True when another copy answers and shows its window.
fn ask_first_copy() -> bool {
    #[cfg(target_os = "linux")]
    if let Some(p) = socket_path() {
        return std::os::unix::net::UnixStream::connect(p)
            .is_ok_and(|c| c.set_read_timeout(Some(TIMEOUT)).is_ok() && ask(c));
    }
    let Ok(addr) = TCP_ADDR.parse() else { return false };
    std::net::TcpStream::connect_timeout(&addr, TIMEOUT).is_ok_and(|c| c.set_read_timeout(Some(TIMEOUT)).is_ok() && ask(c))
}

/// The listener when this is the first copy. Exits when another copy answers.
/// A foreign program on the port does not answer `ok`: this copy then runs without the guard.
pub fn single_instance() -> Option<Listener> {
    if let Some(l) = bind() { return Some(l); }
    // After an update the old copy exits within moments: wait for it, never ask it.
    if std::env::args().any(|a| a == "--after-update") {
        for _ in 0..50 {
            std::thread::sleep(Duration::from_millis(200));
            if let Some(l) = bind() { return Some(l); }
        }
        return None;
    }
    if ask_first_copy() { std::process::exit(0); }
    None
}

/// The first copy: each hello from a second start calls `show`.
pub fn serve(l: Listener, show: impl Fn() + Send + 'static) {
    std::thread::spawn(move || match l {
        Listener::Tcp(l) => for c in l.incoming().flatten() {
            if c.set_read_timeout(Some(TIMEOUT)).is_ok() && answer(c) { show(); }
        },
        #[cfg(target_os = "linux")]
        Listener::Unix(l) => for c in l.incoming().flatten() {
            if c.set_read_timeout(Some(TIMEOUT)).is_ok() && answer(c) { show(); }
        },
    });
}

#[cfg(all(test, target_os = "linux"))]
mod tests {
    use super::*;
    use std::os::unix::net::UnixStream;

    #[test]
    fn a_live_socket_blocks_and_a_left_over_file_is_replaced() {
        let path = std::env::temp_dir().join(format!("quickbuds-test-{}.sock", std::process::id()));
        let _ = std::fs::remove_file(&path);
        let first = bind_unix(&path).expect("first bind");
        assert!(bind_unix(&path).is_none(), "a live copy holds the socket");
        drop(first);
        assert!(path.exists(), "the file stays after the copy ends");
        assert!(bind_unix(&path).is_some(), "a left-over file is replaced");
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn hello_gets_ok() {
        let (a, b) = UnixStream::pair().unwrap();
        let t = std::thread::spawn(move || answer(b));
        assert!(ask(a));
        assert!(t.join().unwrap());
    }
}
