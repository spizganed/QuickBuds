//! The model list (`ModelActivity`): Automatic, then every model in HeyMelody's list by brand. A pick
//! overrides detection until Automatic is picked again or buds with another product id connect.

use crate::protocol::models;
use crate::session::Cmd;
use crate::{load_settings, save_setting, t, with_app, App, MainWindow, ModelRow, ModelSection, Models};
use slint::{ComponentHandle, ModelRc, VecModel};

pub fn setup(main: &MainWindow) {
    main.global::<Models>().on_pick(|id| with_app(|a| {
        save_setting("model_manual", if id.is_empty() { serde_json::Value::Null } else { id.as_str().into() });
        let _ = a.tx.send(Cmd::ModelPicked);
        a.main.global::<Models>().set_current(id);
    }));
    let all = models();
    let name = |m: &serde_json::Value| m["name"].as_str().unwrap_or("").to_string();
    let sections: Vec<ModelSection> = ["OnePlus", "OPPO", "realme", "DIZO"].iter().map(|brand| {
        let mut list: Vec<_> = all.iter().filter(|m| name(m).starts_with(&format!("{brand} "))).collect();
        list.sort_by_key(|m| name(m).to_lowercase());
        let rows: Vec<ModelRow> = list.iter().map(|m| {
            let n = name(m);
            // Two ids share a name (colour ranges, regional variants): those rows show their id.
            let shared = all.iter().filter(|x| name(x) == n).count() > 1;
            let id = m["id"].as_str().unwrap_or("");
            ModelRow { id: id.into(), name: n.as_str().into(), sub: if shared { id.into() } else { "".into() } }
        }).collect();
        ModelSection { title: (*brand).into(), rows: ModelRc::new(VecModel::from(rows)) }
    }).collect();
    let g = main.global::<Models>();
    g.set_sections(ModelRc::new(VecModel::from(sections)));
    g.set_current(load_settings()["model_manual"].as_str().unwrap_or("").into());
}

pub fn apply(a: &App) {
    let s = &a.snap;
    let g = a.main.global::<Models>();
    let detected = s.detected.and_then(|m| m["name"].as_str());
    g.set_detected(match detected {
        Some(n) => t(&a.tr, "model_detected").replace("%1$s", n),
        None => t(&a.tr, "model_not_detected").to_string(),
    }.into());
    // Connected, the session's word counts (buds with another product id reset the pick there).
    if s.status == crate::session::Status::On {
        g.set_current(if s.manual { s.model.and_then(|m| m["id"].as_str()).unwrap_or("") } else { "" }.into());
    }
    g.set_model_name(s.model.and_then(|m| m["name"].as_str()).unwrap_or("").into());
}
