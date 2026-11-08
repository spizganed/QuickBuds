//! The hearing profiles kept on this PC (`settings.json` "hearing_profiles") and the helpers of the hearing test.
//! No UI here.

use crate::types::*;
use crate::{load_settings, save_setting};
use serde_json::{json, Value};

#[derive(Clone)]
pub(crate) struct Record { pub uid: u32, pub name: String, pub values: [i8; 12], pub scan: Vec<u8>, pub desc: u8 }

impl Record {
    pub(crate) fn to_json(&self) -> Value {
        json!({ "uid": self.uid, "name": self.name, "values": self.values.to_vec(), "scan": hex(&self.scan).replace(' ', ""), "desc": self.desc })
    }
    pub(crate) fn from_json(v: &Value) -> Option<Record> {
        let values: Vec<i8> = v["values"].as_array()?.iter().filter_map(|x| x.as_i64().map(|x| x as i8)).collect();
        let s = v["scan"].as_str().unwrap_or("");
        let scan = (0..s.len() / 2).filter_map(|i| u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).ok()).collect();
        Some(Record { uid: v["uid"].as_u64()? as u32, name: v["name"].as_str()?.into(), values: values.try_into().ok()?, scan, desc: v["desc"].as_u64().unwrap_or(0) as u8 })
    }
}

/// `settings.json` "hearing_profiles", newest first, at most 10 (as HeyMelody keeps per buds).
pub(crate) fn records() -> Vec<Record> {
    load_settings()["hearing_profiles"].as_array().into_iter().flatten().filter_map(Record::from_json).collect()
}

pub(crate) fn save_records(list: &[Record]) { save_setting("hearing_profiles", list.iter().map(Record::to_json).collect::<Vec<_>>().into()); }

/// Adds or replaces (same uid) a record.
pub(crate) fn put(r: Record) {
    let mut list = records();
    list.retain(|x| x.uid != r.uid);
    list.insert(0, r);
    list.truncate(10);
    save_records(&list);
}

/// A random number (no RNG crate): the std hasher's per-process random keys over the time.
pub(crate) fn random() -> u64 {
    use std::hash::{BuildHasher, Hasher};
    let mut h = std::collections::hash_map::RandomState::new().build_hasher();
    h.write_u128(std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_nanos()));
    h.finish()
}

/// HeyMelody's record name, the local time "2026/09/29 01:53".
pub(crate) fn now_name() -> String {
    let [y, mo, d, h, mi, ..] = crate::local_now();
    format!("{y:04}/{mo:02}/{d:02} {h:02}:{mi:02}")
}

/// HeyMelody's description id: random within the enhance type's group (0 low 1..6, 1 middle 7..12, else 13..20).
pub(crate) fn desc_id(enhance: u8) -> u8 {
    let (lo, n) = match enhance { 0 => (1, 6), 1 => (7, 6), _ => (13, 8) };
    lo + (random() % n) as u8
}

#[cfg(test)]
mod tests {
    #[test]
    fn records_roundtrip() {
        let r = super::Record { uid: 7, name: "2026/10/04 12:00".into(), values: [-5; 12], scan: vec![0xA8, 1], desc: 3 };
        let back = super::Record::from_json(&r.to_json()).unwrap();
        assert_eq!((back.uid, back.name, back.values, back.scan, back.desc), (7, r.name, r.values, r.scan, 3));
        assert!((1..=6).contains(&super::desc_id(0)) && (13..=20).contains(&super::desc_id(2)));
    }
}
