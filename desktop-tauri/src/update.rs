//! App update from GitHub releases, like the phone's: check on start, then replace the binary in place.
//! `curl` and `tar` do the work (both ship with Windows 10+ and practically every Linux), so no HTTP or
//! archive crate. An AppImage swaps the AppImage file itself (`$APPIMAGE`), as it is the whole app. A binary in a
//! folder we cannot write (a package, `/usr/bin`) is never touched: the release page is offered instead.

use serde_json::Value;
use std::path::{Path, PathBuf};
use std::process::Command;

const WORKER: &str = "https://quickbuds-report.spizganed.workers.dev/latest";
const API: &str = "https://api.github.com/repos/spizganed/QuickBuds/releases?per_page=30";
pub const PAGE: &str = "https://github.com/spizganed/QuickBuds/releases/latest";

/// The AppImage this process runs from: its runtime sets `APPIMAGE` to the file's path.
fn appimage() -> Option<PathBuf> { if cfg!(windows) { None } else { std::env::var_os("APPIMAGE").map(PathBuf::from) } }

/// The release file this build updates from.
fn asset() -> &'static str {
    if cfg!(windows) { "-windows-x64.zip" } else if appimage().is_some() { "-linux-x64.AppImage" } else { "-linux-x64.tar.gz" }
}

pub struct Release { pub version: String, pub url: String, pub sums: Option<String>, pub notes: String }

pub fn quiet(cmd: &str) -> Command {
    #[allow(unused_mut)]
    let mut c = Command::new(cmd);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        c.creation_flags(0x0800_0000); // CREATE_NO_WINDOW: no console flashing up
    }
    c
}

fn curl(args: &[&str]) -> Result<Vec<u8>, String> {
    let out = quiet("curl").args(["-fsSL", "--max-time", "120"]).args(args).output()
        .map_err(|e| format!("curl: {e}"))?;
    if out.status.success() { Ok(out.stdout) } else { Err(String::from_utf8_lossy(&out.stderr).trim().to_string()) }
}

/// "4.3.1" from "v4.3.1" or "desktop-v4.3.1"; `None` for any other tag.
fn version_of(tag: &str) -> Option<&str> { tag.strip_prefix("desktop-v").or_else(|| tag.strip_prefix('v')) }

fn parts(v: &str) -> Vec<u32> { v.split(['.', '-']).map_while(|p| p.parse().ok()).collect() }

pub fn newer(remote: &str, local: &str) -> bool { parts(remote) > parts(local) }

/// The newest release that carries this platform's archive. The Worker answers one release; when it fails
/// or that release has no archive for this platform, GitHub answers once.
pub fn latest() -> Result<Release, String> {
    let one = curl(&[WORKER]).and_then(|b| serde_json::from_slice::<Value>(&b).map_err(|e| e.to_string()));
    if let Ok(r) = one {
        if let Some(r) = pick_one(&r, asset()) { return Ok(r); }
    }
    let list: Value = serde_json::from_slice(&curl(&["-H", "Accept: application/vnd.github+json", API])?)
        .map_err(|e| e.to_string())?;
    pick(&list, asset()).ok_or_else(|| "no release found".into())
}

/// One release, when it is public and carries this platform's archive.
fn pick_one(r: &Value, asset: &str) -> Option<Release> {
    if r["draft"] == true || r["prerelease"] == true { return None; }
    let version = version_of(r["tag_name"].as_str()?)?.to_string();
    let urls: Vec<&str> = r["assets"].as_array()?.iter().filter_map(|a| a["browser_download_url"].as_str()).collect();
    let url = urls.iter().find(|u| u.ends_with(asset))?.to_string();
    let sums = urls.iter().find(|u| u.ends_with("SHA256SUMS")).map(|u| u.to_string());
    let notes = r["body"].as_str().unwrap_or("").trim().to_string();
    Some(Release { version, url, sums, notes })
}

fn pick(list: &Value, asset: &str) -> Option<Release> {
    list.as_array()?.iter()
        .filter_map(|r| pick_one(r, asset))
        .max_by(|a, b| parts(&a.version).cmp(&parts(&b.version)))
}

/// The file an update replaces: the AppImage, else the running binary.
fn exe() -> Option<PathBuf> { appimage().or_else(|| std::env::current_exe().ok())?.canonicalize().ok() }

fn old(exe: &Path) -> PathBuf { exe.with_extension("old") }

/// Whether [install] can replace the running binary: its folder is writable.
pub fn can_install() -> bool {
    let Some(dir) = exe().and_then(|e| e.parent().map(Path::to_path_buf)) else { return false };
    let probe = dir.join(".quickbuds-write-test");
    let ok = std::fs::write(&probe, b"").is_ok();
    let _ = std::fs::remove_file(probe);
    ok
}

/// Leftovers of the last update; run on start.
pub fn clean_up() {
    if let Some(e) = exe() {
        let _ = std::fs::remove_file(old(&e));
        if let Some(dir) = e.parent() { let _ = std::fs::remove_dir_all(dir.join(".quickbuds-update")); }
    }
}

fn find(dir: &Path, name: &std::ffi::OsStr) -> Option<PathBuf> {
    for e in std::fs::read_dir(dir).ok()?.flatten() {
        let p = e.path();
        if p.is_dir() { if let Some(f) = find(&p, name) { return Some(f); } } else if p.file_name() == Some(name) { return Some(p); }
    }
    None
}

/// The last part of a download URL: the file name `SHA256SUMS` lists.
fn file_name(url: &str) -> &str { url.rsplit('/').next().unwrap_or(url) }

/// Checks `path` against the `SHA256SUMS` text. A missing line is an error.
fn verify(path: &Path, url: &str, sums: &str) -> Result<(), String> {
    let name = file_name(url);
    let want = sums.lines().find_map(|l| {
        let mut f = l.split_whitespace();
        let sum = f.next()?;
        (f.next()?.trim_start_matches('*') == name).then_some(sum)
    }).ok_or_else(|| format!("SHA256SUMS has no line for {name}."))?;
    let got = hex(&sha256(&std::fs::read(path).map_err(|e| e.to_string())?));
    if got.eq_ignore_ascii_case(want) { Ok(()) }
    else { Err("The download does not match SHA256SUMS. QuickBuds kept this version.".into()) }
}

fn hex(b: &[u8]) -> String { b.iter().map(|x| format!("{x:02x}")).collect() }

/// SHA-256 (FIPS 180-4). The standard library has no hash and `sha2` is not a dependency.
fn sha256(data: &[u8]) -> [u8; 32] {
    const K: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ];
    let mut h: [u32; 8] = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    let mut msg = data.to_vec();
    msg.push(0x80);
    while msg.len() % 64 != 56 { msg.push(0); }
    msg.extend_from_slice(&((data.len() as u64).wrapping_mul(8)).to_be_bytes());
    for chunk in msg.chunks_exact(64) {
        let mut w = [0u32; 64];
        for i in 0..16 { w[i] = u32::from_be_bytes([chunk[4 * i], chunk[4 * i + 1], chunk[4 * i + 2], chunk[4 * i + 3]]); }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16].wrapping_add(s0).wrapping_add(w[i - 7]).wrapping_add(s1);
        }
        let [mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut hh] = h;
        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ (!e & g);
            let t1 = hh.wrapping_add(s1).wrapping_add(ch).wrapping_add(K[i]).wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let t2 = s0.wrapping_add(maj);
            hh = g; g = f; f = e; e = d.wrapping_add(t1);
            d = c; c = b; b = a; a = t1.wrapping_add(t2);
        }
        for (x, y) in h.iter_mut().zip([a, b, c, d, e, f, g, hh]) { *x = x.wrapping_add(y); }
    }
    let mut out = [0u8; 32];
    for (i, v) in h.iter().enumerate() { out[4 * i..4 * i + 4].copy_from_slice(&v.to_be_bytes()); }
    out
}

/// The size of the file at `url` (after redirects), 0 when the server does not say.
fn size_of(url: &str) -> u64 {
    curl(&["-I", url]).ok().and_then(|h| {
        String::from_utf8_lossy(&h).lines().rev()
            .find_map(|l| l.to_ascii_lowercase().strip_prefix("content-length:").and_then(|v| v.trim().parse().ok()))
    }).unwrap_or(0)
}

/// Downloads `url` to `path`; `progress(done, total)` runs about 10 times a second.
fn download(url: &str, path: &Path, progress: &dyn Fn(u64, u64)) -> Result<(), String> {
    let total = size_of(url);
    let mut child = quiet("curl").args(["-fsSL", "--max-time", "600", "-o"]).arg(path).arg(url)
        .stderr(std::process::Stdio::piped()).spawn().map_err(|e| format!("curl: {e}"))?;
    loop {
        if let Some(st) = child.try_wait().map_err(|e| e.to_string())? {
            if !st.success() {
                let mut err = String::new();
                if let Some(mut e) = child.stderr.take() { let _ = std::io::Read::read_to_string(&mut e, &mut err); }
                return Err(err.trim().to_string());
            }
            progress(total.max(1), total.max(1));
            return Ok(());
        }
        progress(std::fs::metadata(path).map(|m| m.len()).unwrap_or(0), total);
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
}

/// Downloads `r`, swaps the binary (a running one can be renamed on both systems) and starts the new one.
/// The caller quits right after. The download must match the release's `SHA256SUMS`.
pub fn install(r: &Release, progress: &dyn Fn(u64, u64)) -> Result<(), String> {
    let exe = exe().ok_or("cannot find the running binary")?;
    let dir = exe.parent().ok_or("no folder")?;
    let sums_url = r.sums.as_deref().ok_or("This release has no SHA256SUMS file. Update stopped.")?;
    let sums = String::from_utf8_lossy(&curl(&[sums_url])?).into_owned();
    let tmp = dir.join(".quickbuds-update");
    let _ = std::fs::remove_dir_all(&tmp);
    std::fs::create_dir_all(&tmp).map_err(|e| e.to_string())?;
    let new = if appimage().is_some() {
        let new = tmp.join("QuickBuds.AppImage");
        download(&r.url, &new, progress)?;
        verify(&new, &r.url, &sums)?;
        #[cfg(unix)]
        std::fs::set_permissions(&new, std::os::unix::fs::PermissionsExt::from_mode(0o755)).map_err(|e| e.to_string())?;
        new
    } else {
        let archive = tmp.join(if cfg!(windows) { "update.zip" } else { "update.tar.gz" });
        download(&r.url, &archive, progress)?;
        verify(&archive, &r.url, &sums)?;
        let ok = quiet("tar").arg("-xf").arg(&archive).arg("-C").arg(&tmp).status().map_err(|e| format!("tar: {e}"))?;
        if !ok.success() { return Err("could not unpack the download".into()); }
        find(&tmp, exe.file_name().ok_or("name")?).ok_or("the download has no QuickBuds binary")?
    };
    let _ = std::fs::remove_file(old(&exe));
    std::fs::rename(&exe, old(&exe)).map_err(|e| e.to_string())?;
    if let Err(e) = std::fs::rename(&new, &exe) {
        let _ = std::fs::rename(old(&exe), &exe);
        return Err(e.to_string());
    }
    let mut start = host(exe.to_str().ok_or("path")?);
    // Its own process group: it outlives whatever started the old one (a terminal, a launcher).
    #[cfg(unix)]
    std::os::unix::process::CommandExt::process_group(&mut start, 0);
    start.spawn().map_err(|e| e.to_string())?;
    Ok(())
}

/// Opens the release page in the browser.
pub fn open_page() { open_url(PAGE); }

/// Opens [url] in the browser.
pub fn open_url(url: &str) {
    let _ = if cfg!(windows) { quiet("explorer").arg(url).spawn() } else { host("xdg-open").arg(url).spawn() };
}

/// A command for a program outside the AppImage: without the GTK paths its AppRun sets for the bundled GTK
/// (linuxdeploy-plugin-gtk), which break the host's GTK programs, a browser for one.
fn host(cmd: &str) -> Command {
    let mut c = quiet(cmd);
    if let Some(dir) = std::env::var_os("APPDIR").filter(|_| appimage().is_some()) {
        for v in ["GTK_DATA_PREFIX", "GTK_THEME", "GDK_BACKEND", "GSETTINGS_SCHEMA_DIR", "GI_TYPELIB_PATH",
            "GTK_EXE_PREFIX", "GTK_PATH", "GTK_IM_MODULE_FILE", "GDK_PIXBUF_MODULE_FILE"] {
            c.env_remove(v);
        }
        let ours = format!("{}/usr/share:", dir.to_string_lossy());
        if let Ok(dirs) = std::env::var("XDG_DATA_DIRS") { c.env("XDG_DATA_DIRS", dirs.replacen(&ours, "", 1)); }
    }
    c
}

#[cfg(test)]
mod download_tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::sync::Mutex;

    /// A server for one file: answers HEAD with the size, GET with the body in slow chunks.
    fn serve(body: Vec<u8>) -> String {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = l.local_addr().unwrap();
        std::thread::spawn(move || {
            for stream in l.incoming() {
                let Ok(mut s) = stream else { continue };
                let mut buf = [0u8; 1024];
                let n = s.read(&mut buf).unwrap_or(0);
                let head = std::str::from_utf8(&buf[..n]).unwrap_or("").starts_with("HEAD");
                let _ = write!(s, "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", body.len());
                if !head {
                    for c in body.chunks(20_000) { let _ = s.write_all(c); let _ = s.flush(); std::thread::sleep(std::time::Duration::from_millis(60)); }
                }
            }
        });
        format!("http://{addr}/QuickBuds-linux-x64.AppImage")
    }

    #[test]
    fn download_reports_progress_and_writes_the_file() {
        let body: Vec<u8> = (0..200_000u32).map(|i| (i % 251) as u8).collect();
        let url = serve(body.clone());
        assert_eq!(size_of(&url), body.len() as u64);
        let dir = std::env::temp_dir().join(format!("qb-dl-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("file.bin");
        let seen = Mutex::new(Vec::new());
        download(&url, &path, &|done, total| seen.lock().unwrap().push((done, total))).unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), body);
        let seen = seen.into_inner().unwrap();
        assert!(seen.iter().any(|&(d, t)| t == 200_000 && d > 0 && d < t), "no partial progress: {seen:?}");
        assert_eq!(*seen.last().unwrap(), (200_000, 200_000));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn download_fails_on_a_refused_connection() {
        let dir = std::env::temp_dir().join(format!("qb-dl-fail-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        assert!(download("http://127.0.0.1:1/nothing", &dir.join("x"), &|_, _| {}).is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }
}

#[cfg(all(test, unix))]
mod install_tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpListener;

    /// A server with two files: the new AppImage and its SHA256SUMS.
    fn serve(files: Vec<(&'static str, Vec<u8>)>) -> String {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = l.local_addr().unwrap();
        std::thread::spawn(move || {
            for stream in l.incoming() {
                let Ok(mut s) = stream else { continue };
                let mut buf = [0u8; 2048];
                let n = s.read(&mut buf).unwrap_or(0);
                let req = String::from_utf8_lossy(&buf[..n]).into_owned();
                let head = req.starts_with("HEAD");
                let path = req.split_whitespace().nth(1).unwrap_or("/").to_string();
                match files.iter().find(|(name, _)| path.ends_with(name)) {
                    Some((_, body)) => {
                        let _ = write!(s, "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", body.len());
                        if !head { let _ = s.write_all(body); }
                    }
                    None => { let _ = write!(s, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"); }
                }
            }
        });
        format!("http://{addr}")
    }

    /// The whole update on an AppImage: download, SHA256SUMS check, swap, old file kept, new file started.
    #[test]
    fn installs_a_newer_appimage_and_starts_it() {
        let dir = std::env::temp_dir().join(format!("qb-install-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let marker = dir.join("started");
        let current = dir.join("QuickBuds.AppImage");
        std::fs::write(&current, "#!/bin/sh\necho old\n").unwrap();
        let new_body = format!("#!/bin/sh\necho started > {}\n", marker.display()).into_bytes();
        let name = "QuickBuds-linux-x64.AppImage";
        let sums = format!("{}  {name}\n", hex(&sha256(&new_body))).into_bytes();
        let base = serve(vec![(name, new_body.clone()), ("SHA256SUMS", sums)]);
        std::env::set_var("APPIMAGE", &current);
        let r = Release { version: "9.9.9".into(), url: format!("{base}/{name}"), sums: Some(format!("{base}/SHA256SUMS")), notes: String::new() };
        let seen = std::sync::Mutex::new(0u64);
        install(&r, &|done, _| { let mut m = seen.lock().unwrap(); *m = (*m).max(done); }).unwrap();
        assert_eq!(std::fs::read(&current).unwrap(), new_body, "the AppImage was not replaced");
        assert_eq!(std::fs::read(old(&current.canonicalize().unwrap())).unwrap(), b"#!/bin/sh\necho old\n", "the old file is not kept");
        for _ in 0..50 { if marker.exists() { break; } std::thread::sleep(std::time::Duration::from_millis(100)); }
        assert!(marker.exists(), "the new AppImage did not start");
        assert!(*seen.lock().unwrap() > 0);
        // A download that does not match SHA256SUMS changes nothing.
        let bad = serve(vec![(name, b"#!/bin/sh\necho tampered\n".to_vec()), ("SHA256SUMS", format!("{}  {name}\n", hex(&sha256(b"something else"))).into_bytes())]);
        let before = std::fs::read(&current).unwrap();
        let r2 = Release { version: "9.9.10".into(), url: format!("{bad}/{name}"), sums: Some(format!("{bad}/SHA256SUMS")), notes: String::new() };
        assert!(install(&r2, &|_, _| {}).is_err());
        assert_eq!(std::fs::read(&current).unwrap(), before, "a bad download replaced the file");
        std::env::remove_var("APPIMAGE");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
