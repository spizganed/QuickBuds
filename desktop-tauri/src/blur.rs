//! Blur of the desktop behind the window on KDE Wayland (`org_kde_kwin_blur`).
use gdk::prelude::*;
use gtk::prelude::*;
use std::cell::RefCell;
use std::rc::Rc;
use wayland_client::EventQueue;
use wayland_backend::client::{Backend, ObjectId};
use wayland_client::globals::{registry_queue_init, GlobalListContents};
use wayland_client::protocol::{wl_registry::WlRegistry, wl_surface::WlSurface};
use wayland_client::{Connection, Dispatch, Proxy, QueueHandle};
use wayland_protocols_plasma::blur::client::org_kde_kwin_blur::OrgKdeKwinBlur;
use wayland_protocols_plasma::blur::client::org_kde_kwin_blur_manager::OrgKdeKwinBlurManager;

use wayland_client::protocol::{wl_compositor::WlCompositor, wl_region::WlRegion};
use wayland_protocols::ext::background_effect::v1::client::{
    ext_background_effect_manager_v1::ExtBackgroundEffectManagerV1, ext_background_effect_surface_v1::ExtBackgroundEffectSurfaceV1,
};

struct State;
impl Dispatch<ExtBackgroundEffectManagerV1, ()> for State {
    fn event(_: &mut Self, _: &ExtBackgroundEffectManagerV1, _: <ExtBackgroundEffectManagerV1 as Proxy>::Event, _: &(), _: &Connection, _: &QueueHandle<Self>) {}
}
impl Dispatch<ExtBackgroundEffectSurfaceV1, ()> for State {
    fn event(_: &mut Self, _: &ExtBackgroundEffectSurfaceV1, _: <ExtBackgroundEffectSurfaceV1 as Proxy>::Event, _: &(), _: &Connection, _: &QueueHandle<Self>) {}
}
impl Dispatch<WlCompositor, ()> for State {
    fn event(_: &mut Self, _: &WlCompositor, _: <WlCompositor as Proxy>::Event, _: &(), _: &Connection, _: &QueueHandle<Self>) {}
}
impl Dispatch<WlRegion, ()> for State {
    fn event(_: &mut Self, _: &WlRegion, _: <WlRegion as Proxy>::Event, _: &(), _: &Connection, _: &QueueHandle<Self>) {}
}
impl Dispatch<WlRegistry, GlobalListContents> for State {
    fn event(_: &mut Self, _: &WlRegistry, _: wayland_client::protocol::wl_registry::Event, _: &GlobalListContents, _: &Connection, _: &QueueHandle<Self>) {}
}
impl Dispatch<OrgKdeKwinBlurManager, ()> for State {
    fn event(_: &mut Self, _: &OrgKdeKwinBlurManager, _: <OrgKdeKwinBlurManager as Proxy>::Event, _: &(), _: &Connection, _: &QueueHandle<Self>) {}
}
impl Dispatch<OrgKdeKwinBlur, ()> for State {
    fn event(_: &mut Self, _: &OrgKdeKwinBlur, _: <OrgKdeKwinBlur as Proxy>::Event, _: &(), _: &Connection, _: &QueueHandle<Self>) {}
}

struct Live { conn: Connection, queue: EventQueue<State>, comp: WlCompositor, effect: ExtBackgroundEffectSurfaceV1, surface: WlSurface }
type Shared = Rc<RefCell<Option<Live>>>;

/// Ask KWin to blur everything behind `win`, in a rounded shape of `radius` logical px (the CSS uses the same value).
/// GTK destroys the Wayland surface when the window hides, so the effect lives from map to unmap.
/// Does nothing without a blur protocol.
pub fn enable(win: &gtk::ApplicationWindow, radius: i32) {
    let live: Shared = Rc::default();
    let on_map = {
        let live = live.clone();
        move |w: &gtk::ApplicationWindow| {
            match try_enable(w) { Ok(l) => *live.borrow_mut() = l, Err(e) => { eprintln!("blur: {e}"); return; } }
            reshape(&live, w, radius);
        }
    };
    let l = live.clone();
    win.connect_size_allocate(move |w, _| reshape(&l, w, radius));
    win.connect_unmap(move |_| {
        // The effect must go before its surface does, or the compositor ends the connection.
        if let Some(l) = live.borrow_mut().take() { l.effect.destroy(); let _ = l.conn.flush(); }
    });
    if win.is_mapped() { on_map(win) } else { win.connect_map(on_map); }
}

/// Set the blur region to a rounded rectangle of the current window size.
fn reshape(live: &Shared, win: &gtk::ApplicationWindow, radius: i32) {
    let (w, h) = (win.allocated_width(), win.allocated_height());
    let r = radius.min(w / 2).min(h / 2);
    let Some(live) = &*live.borrow() else { return };
    let qh = live.queue.handle();
    let region = live.comp.create_region(&qh, ());
    if r == 0 { region.add(0, 0, w, h); } else {
        for i in 0..r {
            let dy = (r - i) as f64 - 0.5;
            let inset = (r as f64 - ((r * r) as f64 - dy * dy).sqrt()).round() as i32;
            region.add(inset, i, w - 2 * inset, 1);
            region.add(inset, h - 1 - i, w - 2 * inset, 1);
        }
        region.add(0, r, w, h - 2 * r);
    }
    live.effect.set_blur_region(Some(&region));
    region.destroy();
    live.surface.commit();
    let _ = live.conn.flush();
}

/// The live handles of the standard protocol, or `None` when another path (X11, the KDE protocol) set the blur once for good.
fn try_enable(win: &gtk::ApplicationWindow) -> Result<Option<Live>, String> {
    use gdk::glib::translate::ToGlibPtr;
    let gw = win.window().ok_or("no gdk window")?;
    let gd = gw.display();
    if !gd.type_().name().contains("Wayland") { return x11(&gw).map(|_| None); }
    unsafe {
        let gw_ptr: *mut gdk::ffi::GdkWindow = gw.to_glib_none().0;
        let gd_ptr: *mut gdk::ffi::GdkDisplay = gd.to_glib_none().0;
        let wl_display = gdk_wayland_sys::gdk_wayland_display_get_wl_display(gd_ptr as *mut _);
        let wl_surface = gdk_wayland_sys::gdk_wayland_window_get_wl_surface(gw_ptr as *mut _);
        if wl_display.is_null() || wl_surface.is_null() { return Err("no wl pointers".into()); }
        let backend = Backend::from_foreign_display(wl_display as *mut _);
        let conn = Connection::from_backend(backend);
        let surface = WlSurface::from_id(&conn, ObjectId::from_ptr(WlSurface::interface(), wl_surface as *mut _).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
        let (globals, mut queue) = registry_queue_init::<State>(&conn).map_err(|e| e.to_string())?;
        let qh = queue.handle();
        if let Ok(mgr) = globals.bind::<ExtBackgroundEffectManagerV1, _, _>(&qh, 1..=1, ()) {
            // Standard protocol (KWin 6.8 and later). The region covers the whole surface.
            let comp: WlCompositor = globals.bind(&qh, 1..=6, ()).map_err(|e| e.to_string())?;
            let effect = mgr.get_background_effect(&surface, &qh, ());
            std::mem::forget(mgr);
            let _ = queue.roundtrip(&mut State);
            return Ok(Some(Live { conn: conn.clone(), queue, comp, effect, surface: surface.clone() }));
        } else {
            let mgr: OrgKdeKwinBlurManager = globals.bind(&qh, 1..=1, ()).map_err(|e| format!("no blur protocol: {e}"))?;
            let blur = mgr.create(&surface, &qh, ());
            blur.commit();
            surface.commit();
            std::mem::forget(blur);
            std::mem::forget(mgr);
        }
        let _ = queue.roundtrip(&mut State);
        conn.flush().map_err(|e| e.to_string())?;
        std::mem::forget(queue);
        std::mem::forget(conn);
    }
    Ok(None)
}

/// X11: an empty `_KDE_NET_WM_BLUR_BEHIND_REGION` asks the compositor (KWin, picom with a KDE blur patch) to blur the whole window.
fn x11(gw: &gdk::Window) -> Result<(), String> {
    use gdk::glib::translate::ToGlibPtr;
    use std::ffi::CString;
    unsafe {
        let name = CString::new("_KDE_NET_WM_BLUR_BEHIND_REGION").unwrap();
        let kind = CString::new("CARDINAL").unwrap();
        let prop = gdk::ffi::gdk_atom_intern(name.as_ptr(), 0);
        let cardinal = gdk::ffi::gdk_atom_intern(kind.as_ptr(), 0);
        gdk::ffi::gdk_property_change(gw.to_glib_none().0, prop, cardinal, 32, gdk::ffi::GDK_PROP_MODE_REPLACE, std::ptr::null(), 0);
    }
    Ok(())
}
