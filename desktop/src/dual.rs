//! Dual connection (`DualDeviceActivity`): the switch, the devices the buds list and, on models with
//! HeyMelody's device manager, connect / disconnect and the preferred device.

use crate::protocol::*;
use crate::session::{Cmd, Snapshot};
use crate::{t, with_app, App, Choice, Dual, DualDevice, MainWindow};
use slint::{ComponentHandle, ModelRc, VecModel};

/// A device-manager function of this model (`multiConnect`), on buds that take `0x0429`.
fn has(s: &Snapshot, f: &str) -> bool {
    s.model.and_then(|m| m["multiConnect"].as_array()).is_some_and(|l| l.iter().any(|x| x == f))
        && (s.caps.supports(CMD_MULTI_CONNECT) || s.manual)
}

/// The rows shown: only connected devices, every device with the device manager.
fn shown(s: &Snapshot) -> Vec<&PairedDevice> {
    let manages = has(s, "connectDisconnectDevice");
    s.devices.iter().filter(|d| d.connected || manages).collect()
}

pub fn setup(main: &MainWindow) {
    let g = main.global::<Dual>();
    g.on_opened(|| with_app(|a| { let _ = a.tx.send(Cmd::DualReads(has(&a.snap, "setPriorityDevice"))); }));
    g.on_set_dual(|on| with_app(|a| { let _ = a.tx.send(Cmd::Dual(on)); }));
    g.on_connect_device(|i| with_app(|a| {
        if let Some(d) = shown(&a.snap).get(i as usize) { let _ = a.tx.send(Cmd::ConnectDevice(d.mac, !d.connected)); }
    }));
    // 0 = automatic, else a device of the list, 1-based.
    g.on_set_preferred(|v| with_app(|a| {
        let mac = if v == 0 { None } else { a.snap.devices.get(v as usize - 1).map(|d| d.mac) };
        let _ = a.tx.send(Cmd::Preferred(mac));
    }));
}

pub fn apply(a: &App) {
    let s = &a.snap;
    let manages = has(s, "connectDisconnectDevice");
    let rows: Vec<DualDevice> = shown(s).into_iter().map(|d| DualDevice {
        name: d.name.as_str().into(),
        state: t(&a.tr, if d.this_device { "dual_connected_this" } else if d.connected { "dual_connected" } else { "dual_not_connected" }).into(),
        ask: t(&a.tr, if d.connected { "dual_disconnect_q" } else { "dual_connect_q" }).replace("%1$s", &d.name).into(),
        action: t(&a.tr, if d.connected { "conn_action_disconnect" } else { "conn_action_connect" }).into(),
        can_click: manages && !d.this_device,
    }).collect();
    let auto = t(&a.tr, "dual_preferred_auto");
    let choices: Vec<Choice> = std::iter::once(Choice { value: 0, label: auto.into() })
        .chain(s.devices.iter().enumerate().map(|(i, d)| Choice { value: i as i32 + 1, label: d.name.as_str().into() }))
        .collect();
    let g = a.main.global::<Dual>();
    g.set_on(s.features.iter().any(|f| f.0 == FEATURE_DUAL && f.1 == 1));
    crate::update_rows(g.get_devices(), rows, |m| g.set_devices(m));
    g.set_manages(manages);
    g.set_has_preferred(has(s, "setPriorityDevice"));
    g.set_preferred_choices(ModelRc::new(VecModel::from(choices)));
    let i = s.preferred.and_then(|m| s.devices.iter().position(|d| d.mac == m));
    g.set_preferred_current(i.map_or(0, |i| i as i32 + 1));
    // A preferred device no longer listed shows its address.
    g.set_preferred(match (s.preferred, i) {
        (None, _) => auto.into(),
        (Some(_), Some(i)) => s.devices[i].name.as_str().into(),
        (Some(m), None) => m.iter().map(|b| format!("{b:02X}")).collect::<Vec<_>>().join(":").into(),
    });
}
