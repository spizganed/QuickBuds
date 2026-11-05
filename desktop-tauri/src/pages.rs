//! The state of the pages beyond the Overview (gestures, Earbud settings, models) as JSON, and their commands.
//! The rules are the ones of `desktop-egui`; the logic is `gesture_logic` and `home_logic`.

use crate::gesture_logic::*;
use crate::home_logic::{self, ROWS};
use crate::icons;
use crate::session::{Cmd, Snapshot};
use crate::tr;
use crate::types::*;
use serde_json::{json, Value};

/// The JS icon key of a phone icon.
fn icon_key(i: &str) -> &'static str {
    [
        (icons::EARBUD, "earbud"), (icons::EQUALIZER, "eq"), (icons::LOW_LATENCY, "bolt"), (icons::VOLUME, "volume"),
        (icons::FIND_BUDS, "find"), (icons::TRANSPARENCY, "transp"), (icons::HEARING, "hearing"), (icons::GESTURE, "gesture"),
        (icons::DEVICES, "devices"), (icons::POWER, "power"), (icons::ANC, "anc"), (icons::TAP_SINGLE, "tap1"),
        (icons::TAP_DOUBLE, "tap2"), (icons::TAP_TRIPLE, "tap3"), (icons::HOLD, "hold"), (icons::CHEVRON_RIGHT, "slide"),
    ].iter().find(|x| x.0 == i).map_or("earbud", |x| x.1)
}

/// Every string of the phone's resources in the chosen language: the page picks its labels from it.
pub fn strings() -> Value {
    crate::strings::KEYS.iter().map(|k| (k.to_string(), Value::String(tr(k)))).collect::<serde_json::Map<_, _>>().into()
}

fn wear_offered(s: &Snapshot) -> bool {
    s.features.iter().any(|f| f.0 == 0x04)
        || s.model.is_some_and(|m| m["confirmed"].as_array().is_some_and(|c| c.iter().any(|x| x.as_str() == Some("wear"))))
}

fn game_label(v: u8) -> Option<String> {
    Some(tr(match v { 0 => "anc_seg_off", 1 => "game_sound_type_peace", 3 => "game_sound_type_shooter", _ => return None }))
}

fn head_label(v: u8) -> Option<String> {
    Some(tr(match v { 0 => "head_motion_nod", 1 => "head_motion_shake", _ => return None }))
}

fn spatial_label(v: u8) -> String { tr(match v { 1 => "spatial_fixed", 2 => "spatial_head_tracking", _ => "anc_seg_off" }) }

/// (both buds in the ears, any bud in an ear). Without an "in ear" seen, "out" may only mean the model has no sensor: counted as in.
fn worn(s: &Snapshot) -> (bool, bool) {
    let blind = !crate::wear_sensor_seen(s);
    let f = |w: u8| matches!(w, 3 | 7) || blind && matches!(w, 1 | 5);
    (f(s.wear[0]) && f(s.wear[1]), f(s.wear[0]) || f(s.wear[1]))
}

/// The high-quality codec tokens, best first (`ModelCatalog.bestHiResCodec`).
const HIRES_CODECS: [&str; 6] = ["lhdc5", "lhdc", "ldac", "aptx-adaptive", "aptx-hd", "aptx"];

/// The known issues of the current earbuds: the model's `knownIssues`, Hi-Res with 3D audio when both are offered and are
/// not one switch each, and Hi-Res on a model whose `codecs` names no high-quality codec. Each is (title, body).
fn issues(s: &Snapshot) -> Value {
    let listed = |key: &str| s.model.is_some_and(|m| m["knownIssues"].as_array().is_some_and(|k| k.iter().any(|x| x.as_str() == Some(key))));
    let offered = |id: u8| s.features.iter().any(|f| f.0 == id);
    let both = offered(FEATURE_HIRES) && offered(FEATURE_SPATIAL) && s.model.map_or(true, |m| m["spatialToggle"] != 1);
    let hires_row = offered(FEATURE_HIRES) || home_logic::codec_picker(s);
    let no_codec = hires_row && s.model.is_some_and(|m| m["codecs"].as_array().is_some_and(|c| {
        !c.iter().any(|x| x.as_str().is_some_and(|t| HIRES_CODECS.contains(&t)))
    }));
    let mut out = vec![];
    if listed("spatial_silent") { out.push(json!({ "title": tr("issue_spatial_silent_title"), "body": tr("issue_spatial_silent_body") })); }
    if both { out.push(json!({ "title": tr("issue_hires_spatial_title"), "body": tr("issue_hires_spatial_body") })); }
    if listed("hires_no_codec") || no_codec { out.push(json!({ "title": tr("issue_hires_no_codec_title"), "body": tr("issue_hires_no_codec_body") })); }
    json!(out)
}

/// The gestures page: the model's rows, each with its options and the binding on both buds.
pub fn gestures_json(s: &Snapshot) -> Value {
    let m = Model::of(s);
    let available = s.caps.supports(CMD_SET_KEY_FUNCTION) && !m.rows.is_empty();
    let actions: Vec<Value> = ACTIONS.iter().enumerate().map(|(i, a)| json!({
        "i": i, "label": tr(a.0), "short": tr(if a.1.is_empty() { a.0 } else { a.1 }), "noise": a.4 >= 0,
    })).collect();
    let cur = |side: u8, g: usize| current(s, &m, side, g).map(|c| json!(c)).unwrap_or(Value::Null);
    let rows: Vec<Value> = m.rows.iter().map(|(g, opts)| json!({
        "g": g, "label": tr(GESTURES[*g].0), "icon": icon_key(GESTURES[*g].4), "options": opts,
        "hold": *g == TAP_HOLD, "cur": [cur(1, *g), cur(2, *g)],
        // A per-bud hold offers a choice first: the noise cycle or one action.
        "choices": if *g == TAP_HOLD && m.per_bud {
            m.hold_choices.iter().filter_map(|b| if *b == HOLD_NOISE { Some(json!({ "v": -1, "label": tr("anc_section") })) }
                else { ACTIONS.iter().position(|x| x.3 == *b).map(|x| json!({ "v": x, "label": tr(ACTIONS[x].0) })) }).collect::<Vec<_>>()
        } else { vec![] },
    })).collect();
    let calls: Vec<Value> = m.on_call.iter().map(|k| json!({
        "k": k, "label": tr(ON_CALL[*k].0), "on_label": tr(ON_CALL[*k].1), "icon": icon_key(ON_CALL[*k].5), "on": call_on(s, *k),
    })).collect();
    json!({
        "available": available, "oneButton": m.one_button, "perBud": m.per_bud, "holdMin": m.hold_min,
        "actions": actions, "rows": rows, "calls": calls,
        "bothHold": if m.both_hold { json!({ "on": both_hold_on(s) }) } else { Value::Null },
    })
}

/// The Earbud settings extras: the feature rows, pickers, Personalized ANC and the dialogs' state.
pub fn earbud_json(s: &Snapshot) -> Value {
    let flagged = |flag: Option<&str>| s.manual && flag.is_some_and(|f| s.model.is_some_and(|m| m[f] == 1));
    let mut rows: Vec<Value> = vec![];
    for &(id, flag, icon, title, sub, confirm, confirm_off) in ROWS {
        if id == 0x04 { continue; }
        let v = match s.features.iter().find(|f| f.0 == id) { Some(f) => f.1, None if flagged(flag) => 0, None => continue };
        let pick = match id {
            0x27 if s.manual || s.caps.supports(CMD_GAME_SOUND) => {
                let offered = s.game_sound.as_ref().map(|g| g.1.clone()).filter(|v| !v.is_empty()).unwrap_or(vec![3]);
                let mut game: Vec<u8> = vec![0];
                for v in offered { if !game.contains(&v) { game.push(v); } }
                Some(json!({ "kind": 1, "title": tr("game_sound_type_title"), "cur": s.game_sound.as_ref().map(|g| g.0),
                    "label": s.game_sound.as_ref().and_then(|g| game_label(g.0)),
                    "choices": game.into_iter().filter_map(|v| Some(json!({ "v": v, "label": game_label(v)? }))).collect::<Vec<_>>() }))
            }
            0x3B if s.caps.supports(CMD_SET_HEAD_MOTION) => Some(json!({ "kind": 2, "title": tr("head_motion_type_title"), "cur": s.head_motion,
                "label": s.head_motion.and_then(head_label),
                "choices": ([0u8, 1].into_iter().filter_map(|v| Some(json!({ "v": v, "label": head_label(v)? }))).collect::<Vec<_>>()) })),
            _ => None,
        };
        rows.push(json!({ "id": id, "icon": icon_key(icon), "title": tr(title), "sub": tr(sub), "on": v == 1, "busy": s.busy.contains(&id),
            "confirm": confirm.map(tr), "confirmOff": confirm_off, "pick": pick }));
    }
    // Game equalizer: the buds list feature 0x21 and accept 0x0420.
    if let Some(v) = s.features.iter().find(|f| f.0 == FEATURE_GAME_EQ).map(|f| f.1).filter(|_| s.caps.supports(CMD_GAME_EQ_STATE)) {
        let at = rows.iter().position(|r| r["id"] == 0x27 || r["id"] == 0x1C).map_or(rows.len(), |p| p + (rows[p]["id"] == 0x27) as usize);
        rows.insert(at, json!({ "id": FEATURE_GAME_EQ, "icon": "bolt", "title": tr("row_game_eq_title"), "sub": tr("row_game_eq_sub"), "on": v == 1,
            "busy": s.busy.contains(&FEATURE_GAME_EQ), "confirm": null, "confirmOff": false, "pick": null }));
    }
    let pnc_listed = s.features.iter().find(|f| f.0 == FEATURE_PERSONAL_NOISE).map(|f| f.1);
    let pnc = s.model.map_or(true, |m| m["personalAnc"] == 1)
        && (pnc_listed.is_some() || flagged(Some("personalAnc")))
        && (s.caps.supports(CMD_PERSONAL_NOISE) || s.manual);
    let picker = home_logic::codec_picker(s);
    let hires_on = s.features.contains(&(FEATURE_HIRES, 1));
    json!({
        "wear": wear_offered(s),
        "worn": worn(s).0,
        "wornAny": worn(s).1,
        "issues": issues(s),
        "smartPause": crate::load_settings()["smart_auto_pause"].as_bool() == Some(true),
        "find": s.caps.supports(CMD_FIND_BUDS) && !s.model.is_some_and(|m| m["noFind"] == 1),
        "fit": s.caps.supports(CMD_FIT_TEST),
        "fitState": { "l": s.fit.0, "r": s.fit.1, "seq": s.fit.2 },
        "pncState": { "kind": s.pnc.0, "value": s.pnc.1, "seq": s.pnc.2 },
        "pnc": if pnc { json!({ "on": pnc_listed == Some(1) }) } else { Value::Null },
        "rows": rows,
        "firmwareRow": s.caps.supports(CMD_QUERY_FIRMWARE),
        "firmware": s.firmware,
        "gestures": gestures_json(s)["available"],
        // High-quality audio and 3D audio: the confirmation texts and the pickers of the Overview rows.
        "hq": {
            "confirm": tr(if !picker && home_logic::spatial_on(s) && home_logic::exclusive(s) { "codec_msg_hires_drops_spatial" } else { "codec_msg_reconnect" }),
            "picker": picker, "codec": s.codec.map(codec_name), "locked": picker && !matches!(s.codec, Some(3 | 8)),
            "choices": ([8u8, 7, 3, 6, 5, 4, 2, 1].into_iter().filter(|c| s.codecs.contains(c)).map(|c| json!({ "v": c, "label": codec_name(c) })).collect::<Vec<_>>()),
            "cur": s.codec, "on": hires_on,
        },
        "spatial": {
            "confirm": if hires_on && home_logic::exclusive(s) { Some(tr("codec_msg_spatial_drops_hires")) } else { None },
            "head": home_logic::head_tracking(s), "cur": s.spatial_type.unwrap_or(0), "label": spatial_label(s.spatial_type.unwrap_or(0)),
            "choices": (0..3u8).map(|v| json!({ "v": v, "label": spatial_label(v) })).collect::<Vec<_>>(),
        },
        "wind": {
            "has": s.features.iter().any(|f| f.0 == FEATURE_WIND_NOISE) || (s.manual && s.model.is_some_and(|m| m["windMode"] == 1)),
            "on": s.features.contains(&(FEATURE_WIND_NOISE, 1)),
        },
        "gameBusy": s.busy.contains(&s.caps.game_mode_id()),
    })
}

/// The model list by brand: [(brand, [(id, name, id shown or empty)])].
pub fn models_json(s: &Snapshot) -> Value {
    let all = models();
    let name = |m: &Value| m["name"].as_str().unwrap_or("").to_string();
    let sections: Vec<Value> = ["OnePlus", "OPPO", "realme", "DIZO"].iter().map(|brand| {
        let mut list: Vec<&Value> = all.iter().filter(|m| name(m).starts_with(&format!("{brand} "))).collect();
        list.sort_by_key(|m| name(m).to_lowercase());
        let rows: Vec<Value> = list.iter().map(|m| {
            let n = name(m);
            let shared = all.iter().filter(|x| name(x) == n).count() > 1;
            let id = m["id"].as_str().unwrap_or("");
            json!({ "id": id, "name": n, "sub": if shared { id } else { "" } })
        }).collect();
        json!({ "brand": brand, "rows": rows })
    }).collect();
    let detected = s.detected.and_then(|m| m["name"].as_str());
    json!({
        "sections": sections,
        "current": if s.manual { s.model.and_then(|m| m["id"].as_str()).unwrap_or("") } else { "" },
        "detected": match detected {
            Some(n) => tr("model_detected").replace("%1$s", n),
            None => tr("model_not_detected"),
        },
    })
}

/// A device-manager function of this model (`dualDevice`), on buds that take `0x0429`.
fn dual_has(s: &Snapshot, f: &str) -> bool {
    s.model.and_then(|m| m["dualDevice"].as_array()).is_some_and(|l| l.iter().any(|x| x == f)) && (s.caps.supports(CMD_MULTI_CONNECT) || s.manual)
}

/// The rows shown: only connected devices, every device with the device manager.
fn dual_shown(s: &Snapshot) -> Vec<&PairedDevice> {
    let manages = dual_has(s, "connectDisconnectDevice");
    s.devices.iter().filter(|d| d.connected || manages).collect()
}

/// Dual connection: the devices the buds list, and on models with the device manager the preferred device.
pub fn dual_json(s: &Snapshot) -> Value {
    let manages = dual_has(s, "connectDisconnectDevice");
    let idx = s.preferred.and_then(|m| s.devices.iter().position(|d| d.mac == m));
    let preferred = match (s.preferred, idx) {
        (None, _) => tr("dual_preferred_auto"),
        (Some(_), Some(i)) => s.devices[i].name.clone(),
        (Some(m), None) => m.iter().map(|b| format!("{b:02X}")).collect::<Vec<_>>().join(":"),
    };
    json!({
        "manages": manages, "hasPreferred": dual_has(s, "setPriorityDevice"), "preferred": preferred, "preferredCur": idx.map_or(0, |i| i + 1),
        "preferredChoices": std::iter::once(tr("dual_preferred_auto")).chain(s.devices.iter().map(|d| d.name.clone())).collect::<Vec<_>>(),
        "devices": dual_shown(s).iter().map(|d| json!({
            "name": d.name, "connected": d.connected, "this": d.this_device, "can": manages && !d.this_device,
            "state": tr(if d.this_device { "dual_connected_this" } else if d.connected { "dual_connected" } else { "dual_not_connected" }),
            "ask": tr(if d.connected { "dual_disconnect_q" } else { "dual_connect_q" }).replace("%1$s", &d.name),
            "action": tr(if d.connected { "conn_action_disconnect" } else { "conn_action_connect" }),
        })).collect::<Vec<_>>(),
    })
}

/// The commands of these pages, as `{ "t": "...", ... }`.
pub fn commands(s: &Snapshot, cmd: &Value) -> Option<Vec<Cmd>> {
    let id = cmd["id"].as_u64().unwrap_or(0) as u8;
    let on = cmd["on"].as_bool().unwrap_or(false);
    let v = cmd["v"].as_u64().unwrap_or(0) as u8;
    Some(match cmd["t"].as_str()? {
        "gestures_open" => vec![Cmd::ControlsReads(Model::of(s).hold_types())],
        "gesture" => {
            let m = Model::of(s);
            let g = cmd["g"].as_u64()? as usize;
            let side = cmd["side"].as_u64().unwrap_or(1) as u8;
            let actions: Vec<usize> = cmd["actions"].as_array()?.iter().filter_map(|x| x.as_u64().map(|x| x as usize)).collect();
            // Only an action the row offers; the hold's cycle never goes below the model's minimum.
            if g >= GESTURES.len() || actions.iter().any(|a| *a >= ACTIONS.len()) { return None; }
            if g == TAP_HOLD && !actions.is_empty() && actions.iter().all(|a| ACTIONS[*a].4 >= 0) && actions.len() < m.hold_min { return None; }
            write_cmds(s, &m, side, g, &actions)
        }
        "both_hold" => vec![Cmd::OnCall(BUTTON_PRIMARY, 4, if on { 0x11 } else { 0 })],
        "call" => {
            let k = cmd["k"].as_u64()? as usize;
            if k >= ON_CALL.len() { return None; }
            vec![Cmd::OnCall(BUTTON_ON_CALL, ON_CALL[k].2, if on { ON_CALL[k].3 } else { 0 })]
        }
        "feature" => home_logic::feature_cmds(s, id, on),
        "game_sound" => vec![Cmd::GameSoundType(v)],
        "head_motion" => vec![Cmd::HeadMotion(v)],
        "codec" => vec![Cmd::Codec(v, s.features.contains(&(FEATURE_HIRES, 1)))],
        "spatial" => home_logic::spatial_cmds(s, v),
        "pnc_feature" => vec![Cmd::Features(vec![(FEATURE_PERSONAL_NOISE, on)])],
        "fit" => vec![Cmd::FitTest(on)],
        "pnc_query" => vec![Cmd::PncQuery],
        "pnc" => vec![Cmd::Pnc(v)],
        "status_read" => vec![Cmd::StatusRead],
        "wind" => vec![Cmd::Features(vec![(FEATURE_WIND_NOISE, on)])],
        "dual_open" => vec![Cmd::DualReads(dual_has(s, "setPriorityDevice"))],
        "dev_toggle" => {
            let d = *dual_shown(s).get(cmd["i"].as_u64()? as usize)?;
            if !dual_has(s, "connectDisconnectDevice") || d.this_device { return None; }
            vec![Cmd::ConnectDevice(d.mac, !d.connected)]
        }
        // 0 = automatic, else a device of the list, 1-based.
        "preferred" => {
            let v = cmd["v"].as_u64()? as usize;
            vec![Cmd::Preferred(if v == 0 { None } else { s.devices.get(v - 1).map(|d| d.mac) })]
        }
        "model" => {
            crate::save_setting("model_manual", cmd["id"].as_str().filter(|x| !x.is_empty()).map_or(Value::Null, |x| x.into()));
            vec![Cmd::ModelPicked]
        }
        _ => return None,
    })
}
