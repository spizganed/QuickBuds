//! Runs every scenario of `feature_sync_scenarios.json`: the contract both the phone and the desktop
//! must keep (docs/FEATURE-SYNC.md).

use buds_core::feature_sync::{Confirm, FeatureSync, Operation, Out};
use serde_json::Value;

/// "24" -> 24.
fn id(text: &str) -> u8 {
    text.parse().unwrap_or_else(|_| panic!("not a feature id: {text}"))
}

fn num(v: &Value) -> u64 {
    v.as_u64().unwrap_or_else(|| panic!("not a number: {v}"))
}

fn confirm(text: &str) -> Confirm {
    match text {
        "read" => Confirm::Read,
        "push" => Confirm::Push,
        "ack" => Confirm::Ack,
        other => panic!("not a confirm kind: {other}"),
    }
}

fn operation(ev: &Value) -> Operation {
    let writes = ev["writes"].as_array().unwrap_or_else(|| panic!("no writes: {ev}"))
        .iter()
        .map(|w| {
            let w = w.as_array().unwrap_or_else(|| panic!("not a write: {w}"));
            (num(&w[0]) as u8, num(&w[1]) as u8)
        })
        .collect();
    Operation { key: num(&ev["key"]) as u8, writes, confirm: confirm(ev["confirm"].as_str().unwrap()), restarts: ev["restarts"].as_bool().unwrap_or(false) }
}

fn values(v: &Value) -> Vec<(u8, u8)> {
    v.as_object().unwrap_or_else(|| panic!("not a value map: {v}"))
        .iter()
        .map(|(k, v)| (id(k), num(v) as u8))
        .collect()
}

fn tokens(outs: &[Out]) -> Vec<String> {
    outs.iter().map(Out::token).collect()
}

#[test]
fn every_scenario_passes() {
    let text = include_str!("feature_sync_scenarios.json");
    let root: Value = serde_json::from_str(text).expect("the scenario file is valid JSON");
    let scenarios = root["scenarios"].as_array().expect("scenarios");
    assert!(!scenarios.is_empty(), "no scenarios");

    for sc in scenarios {
        let name = sc["name"].as_str().unwrap_or("?");
        let mut engine = FeatureSync::new();
        engine.init(values(&sc["init"]));

        for ev in sc["events"].as_array().unwrap_or_else(|| panic!("{name}: no events")) {
            let now = num(&ev["now"]);
            let outs = match ev["ev"].as_str().unwrap() {
                "tap" => engine.tap(operation(ev), now),
                "ack" => engine.ack(num(&ev["seq"]) as u32, ev["status"].as_u64().unwrap_or(0) as u8, now),
                "report" => {
                    let seq = ev["seq"].as_u64().map(|s| s as u32);
                    engine.report(&values(&ev["values"]), seq, now, ev["push"].as_bool().unwrap_or(false))
                }
                "tick" => engine.tick(now),
                "disconnect" => engine.disconnect(now),
                "reconnect" => engine.reconnect(now),
                other => panic!("{name}: unknown event {other}"),
            };
            let got = tokens(&outs);
            let want: Vec<String> = ev["expect"].as_array().unwrap_or_else(|| panic!("{name}: no expect"))
                .iter().map(|t| t.as_str().unwrap().to_string()).collect();
            assert_eq!(got, want, "{name}: event {ev}");
        }
    }
}
