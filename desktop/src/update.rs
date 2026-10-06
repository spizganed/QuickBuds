//! App update from GitHub releases, like the phone's: check on start, then replace the binary in place.
//! `curl` and `tar` do the work (both ship with Windows 10+ and practically every Linux), so no HTTP or
//! archive crate. A binary in a folder we cannot write (a package, `/usr/bin`) is never touched: the
//! release page is offered instead.

use serde_json::Value;
use std::path::{Path, PathBuf};
use std::process::Command;

const API: &str = "https://api.github.com/repos/spizganed/QuickBuds/releases?per_page=30";
pub const PAGE: &str = "https://github.com/spizganed/QuickBuds/releases/latest";
const ASSET: &str = if cfg!(windows) { "-windows-x64.zip" } else { "-linux-x64.tar.gz" };

pub struct Release { pub version: String, pub url: String }

fn quiet(cmd: &str) -> Command {
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

/// The newest release that carries this platform's archive.
pub fn latest() -> Result<Release, String> {
    let list: Value = serde_json::from_slice(&curl(&["-H", "Accept: application/vnd.github+json", API])?)
        .map_err(|e| e.to_string())?;
    pick(&list).ok_or_else(|| "no release found".into())
}

fn pick(list: &Value) -> Option<Release> {
    list.as_array()?.iter()
        .filter(|r| r["draft"] != true && r["prerelease"] != true)
        .filter_map(|r| {
            let version = version_of(r["tag_name"].as_str()?)?.to_string();
            let url = r["assets"].as_array()?.iter()
                .filter_map(|a| a["browser_download_url"].as_str())
                .find(|u| u.ends_with(ASSET))?.to_string();
            Some(Release { version, url })
        })
        .max_by(|a, b| parts(&a.version).cmp(&parts(&b.version)))
}

fn exe() -> Option<PathBuf> { std::env::current_exe().ok()?.canonicalize().ok() }

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

/// Downloads `r`, swaps the binary (a running one can be renamed on both systems) and starts the new one.
/// The caller quits right after.
pub fn install(r: &Release) -> Result<(), String> {
    let exe = exe().ok_or("cannot find the running binary")?;
    let dir = exe.parent().ok_or("no folder")?;
    let tmp = dir.join(".quickbuds-update");
    let _ = std::fs::remove_dir_all(&tmp);
    std::fs::create_dir_all(&tmp).map_err(|e| e.to_string())?;
    let archive = tmp.join(if cfg!(windows) { "update.zip" } else { "update.tar.gz" });
    curl(&["-o", archive.to_str().ok_or("path")?, &r.url])?;
    let ok = quiet("tar").arg("-xf").arg(&archive).arg("-C").arg(&tmp).status().map_err(|e| format!("tar: {e}"))?;
    if !ok.success() { return Err("could not unpack the download".into()); }
    let new = find(&tmp, exe.file_name().ok_or("name")?).ok_or("the download has no QuickBuds binary")?;
    let _ = std::fs::remove_file(old(&exe));
    std::fs::rename(&exe, old(&exe)).map_err(|e| e.to_string())?;
    if let Err(e) = std::fs::rename(&new, &exe) {
        let _ = std::fs::rename(old(&exe), &exe);
        return Err(e.to_string());
    }
    let mut start = quiet(exe.to_str().ok_or("path")?);
    // Its own process group: it outlives whatever started the old one (a terminal, a launcher).
    #[cfg(unix)]
    std::os::unix::process::CommandExt::process_group(&mut start, 0);
    start.spawn().map_err(|e| e.to_string())?;
    Ok(())
}

/// Opens the release page in the browser.
pub fn open_page() {
    let _ = if cfg!(windows) { quiet("explorer").arg(PAGE).spawn() } else { Command::new("xdg-open").arg(PAGE).spawn() };
}

// --- The App settings section and the overview notice ---

use crate::{load_settings, save_setting, t, with_app, App, MainWindow, Update};
use slint::ComponentHandle;
use std::sync::Mutex;

/// The release last found newer than this build.
static FOUND: Mutex<Option<Release>> = Mutex::new(None);

pub fn setup(main: &MainWindow) {
    clean_up();
    let g = main.global::<Update>();
    g.set_installed(env!("CARGO_PKG_VERSION").into());
    let auto = load_settings()["update_auto"].as_bool().unwrap_or(true);
    g.set_auto(auto);
    g.on_check(|| with_app(check));
    g.on_set_auto(|on| with_app(|a| { save_setting("update_auto", on.into()); a.main.global::<Update>().set_auto(on); }));
    g.set_clear_battery(load_settings()["clear_battery"].as_bool().unwrap_or(false));
    g.on_set_clear_battery(|on| with_app(|a| { save_setting("clear_battery", on.into()); a.main.global::<Update>().set_clear_battery(on); }));
    g.on_install(|| with_app(|a| {
        let Some(r) = FOUND.lock().unwrap().take() else { return };
        if !can_install() { open_page(); *FOUND.lock().unwrap() = Some(r); return; }
        let g = a.main.global::<Update>();
        g.set_busy(true);
        g.set_status("Installing…".into());
        std::thread::spawn(move || {
            let res = install(&r);
            let _ = slint::invoke_from_event_loop(move || match res {
                Ok(()) => { let _ = slint::quit_event_loop(); }
                Err(e) => with_app(|a| {
                    *FOUND.lock().unwrap() = Some(r);
                    let g = a.main.global::<Update>();
                    g.set_busy(false);
                    g.set_status(t(&a.tr, "update_failed").replace("%1$s", &e).into());
                }),
            });
        });
    }));
    if auto { with_app(check); }
}

fn check(a: &mut App) {
    let g = a.main.global::<Update>();
    if g.get_busy() { return; }
    g.set_busy(true);
    g.set_status(t(&a.tr, "update_checking").into());
    std::thread::spawn(|| {
        let res = latest();
        let _ = slint::invoke_from_event_loop(move || with_app(|a| {
            let g = a.main.global::<Update>();
            g.set_busy(false);
            match res {
                Ok(r) if newer(&r.version, env!("CARGO_PKG_VERSION")) => {
                    g.set_available(r.version.as_str().into());
                    g.set_notice(t(&a.tr, "update_found_title").replace("%1$s", &r.version).into());
                    g.set_can_install(can_install());
                    g.set_status(t(&a.tr, "update_available").replace("%1$s", &r.version).into());
                    *FOUND.lock().unwrap() = Some(r);
                }
                Ok(_) => { g.set_available("".into()); g.set_status(t(&a.tr, "update_up_to_date").into()); }
                Err(e) => g.set_status(t(&a.tr, "update_failed").replace("%1$s", &e).into()),
            }
        }));
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn picks_newest_with_our_archive() {
        let list = serde_json::json!([
            { "tag_name": "v4.4.0", "prerelease": true, "assets": [{ "browser_download_url": format!("x/a{ASSET}") }] },
            { "tag_name": "desktop-v4.3.2", "assets": [{ "browser_download_url": format!("x/b{ASSET}") }] },
            { "tag_name": "v4.3.1", "assets": [{ "browser_download_url": format!("x/c{ASSET}") }] },
            { "tag_name": "v9.0.0", "assets": [{ "browser_download_url": "x/QuickBuds9.0.0.apk" }] },
        ]);
        let r = pick(&list).unwrap();
        assert_eq!((r.version.as_str(), r.url.as_str()), ("4.3.2", format!("x/b{ASSET}").as_str()));
        assert!(newer("4.3.1", "4.3.0") && newer("4.10.0", "4.9.9") && !newer("4.3.0", "4.3.0-beta") && !newer("4.3.0", "4.3.1"));
    }
}
