//! The Linux tray icon (StatusNotifierItem). The Tauri tray reports no clicks on Linux, so this one does.
//! A left click and a right click both open the quick panel; there is no menu.

use tauri::AppHandle;

struct Tray {
    app: AppHandle,
    /// ARGB32, the byte order the StatusNotifierItem spec asks for.
    icon: ksni::Icon,
}

impl Tray {
    fn open_panel(&self) {
        let app = self.app.clone();
        let _ = self.app.run_on_main_thread(move || crate::show_panel(&app));
    }
}

impl ksni::Tray for Tray {
    fn id(&self) -> String { "quickbuds".into() }
    fn title(&self) -> String { "QuickBuds".into() }
    fn icon_pixmap(&self) -> Vec<ksni::Icon> { vec![ksni::Icon { width: self.icon.width, height: self.icon.height, data: self.icon.data.clone() }] }
    fn activate(&mut self, _x: i32, _y: i32) { self.open_panel(); }
    // The host asks for the menu on a right click. The menu is empty, so the panel opens instead.
    fn menu_about_to_show(&mut self) { self.open_panel(); }
}

pub fn start(app: &AppHandle) {
    use ksni::blocking::TrayMethods;
    // The tray icon is the logo cropped to its edges: the window icon has wide margins and looks small in a tray.
    let Ok(img) = tauri::image::Image::from_bytes(include_bytes!("../icons/tray.png")) else { return };
    let mut data = Vec::with_capacity(img.rgba().len());
    for px in img.rgba().chunks_exact(4) { data.extend_from_slice(&[px[3], px[0], px[1], px[2]]); }
    let tray = Tray { app: app.clone(), icon: ksni::Icon { width: img.width() as i32, height: img.height() as i32, data } };
    match tray.spawn() {
        // The handle keeps the icon alive for as long as the app runs.
        Ok(handle) => std::mem::forget(handle),
        Err(e) => eprintln!("tray: {e}"),
    }
}
