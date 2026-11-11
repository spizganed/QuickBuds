//! The `0x8100` bit table against `bitmap_table.json`. `CapabilitiesTest.kt` runs the same file.

use std::collections::BTreeSet;

use buds_core::msg::Bitmap;
use serde_json::Value;

fn hex(v: &Value) -> Vec<u16> {
    v.as_array().unwrap().iter().map(|h| u16::from_str_radix(h.as_str().unwrap().trim_start_matches("0x"), 16).unwrap()).collect()
}

#[test]
fn every_bit_matches_the_file() {
    let file: Value = serde_json::from_str(include_str!("bitmap_table.json")).unwrap();
    let always = hex(&file["always"]);
    let bits = file["bits"].as_array().unwrap();
    let set = |b: &Bitmap| b.commands().unwrap().iter().copied().collect::<BTreeSet<u16>>();
    assert_eq!(set(&Bitmap::parse(&[0; 10]).unwrap()), always.iter().copied().collect());
    for (bit, cmds) in bits.iter().enumerate() {
        let mut p = [0u8; 10];
        p[1 + bit / 8] = 1 << (bit % 8);
        let want: BTreeSet<u16> = always.iter().chain(hex(cmds).iter()).copied().collect();
        assert_eq!(set(&Bitmap::parse(&p).unwrap()), want, "bit {bit}");
    }
    let mut past = [0u8; 10];
    past[1 + bits.len() / 8] = 1 << (bits.len() % 8);
    assert_eq!(Bitmap::parse(&past).unwrap().commands().unwrap().len(), always.len(), "bits past the table are ignored");
}
