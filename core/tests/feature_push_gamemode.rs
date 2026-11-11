//! Decode of the `0x0503` feature push and the game-mode byte rule.
//! The frames come from `.orchestration/out/pb-frames/frames.json`.

use std::collections::BTreeMap;

use buds_core::msg::{decode, Inbound};
use buds_core::wire::{Message, Reassembler};

fn bytes(hex: &str) -> Vec<u8> {
    hex.split_whitespace().map(|b| u8::from_str_radix(b, 16).unwrap()).collect()
}

fn decode_frame(hex: &str) -> Option<Inbound> {
    let mut r = Reassembler::default();
    let messages = r.push(&bytes(hex));
    assert_eq!(messages.len(), 1, "{hex}");
    decode(&messages[0])
}

fn message(cmd: u16, payload: &[u8]) -> Message {
    Message { cmd, seq: 0xFF, payload: payload.to_vec() }
}

fn feature_push(pairs: &[(u8, u8)]) -> Inbound {
    Inbound::FeaturePush(pairs.iter().copied().collect::<BTreeMap<_, _>>())
}

fn game_mode(at1: Option<u8>, at3: Option<u8>) -> Inbound {
    Inbound::GameMode { at1, at3 }
}

#[test]
fn feature_push_one_pair() {
    // featurepush-01: game mode 06 is 1.
    assert_eq!(decode_frame("AA 0A 00 00 03 05 FF 03 00 01 06 01"), Some(feature_push(&[(0x06, 1)])));
}

#[test]
fn feature_push_ignores_the_trailing_byte() {
    // featurepush-02: one byte follows the pair.
    assert_eq!(decode_frame("AA 0B 00 00 03 05 FF 04 00 01 1B 01 30"), Some(feature_push(&[(0x1B, 1)])));
    assert_eq!(decode(&message(0x0503, &[0x01, 0x1B, 0x00, 0x47])), Some(feature_push(&[(0x1B, 0)])));
}

#[test]
fn feature_push_seventeen_pairs() {
    // featurepush-03: 0x11 = 17 pairs in 35 bytes.
    let Some(Inbound::FeaturePush(values)) = decode_frame(
        "AA 2A 00 00 03 05 FF 23 00 11 00 00 01 00 02 00 04 01 05 00 \
         06 01 08 00 09 00 0B 00 0C 00 0D 00 10 00 11 00 18 00 1A 01 \
         1B 00 1D 00",
    ) else {
        panic!()
    };
    assert_eq!(values.len(), 17);
    assert_eq!(values.get(&0x06), Some(&1));
    assert_eq!(values.get(&0x04), Some(&1));
    assert_eq!(values.get(&0x1B), Some(&0));
    assert_eq!(values.get(&0x1A), Some(&1));
}

#[test]
fn feature_push_rejects_a_truncated_payload() {
    assert_eq!(decode(&message(0x0503, &[0x02, 0x06, 0x01])), None);
    assert_eq!(decode(&message(0x0503, &[0x01])), None);
    assert_eq!(decode(&message(0x0503, &[])), None);
}

#[test]
fn feature_push_accepts_a_count_of_zero() {
    assert_eq!(decode(&message(0x0503, &[0x00])), Some(Inbound::FeaturePush(BTreeMap::new())));
}

#[test]
fn feature_push_keeps_an_unknown_id() {
    assert_eq!(decode(&message(0x0503, &[0x01, 0x7F, 0x01])), Some(feature_push(&[(0x7F, 1)])));
}

#[test]
fn game_mode_062410_uses_byte_3() {
    assert_eq!(game_mode(Some(0), Some(1)).game_mode_state(Some("062410")), Some(true));
    assert_eq!(game_mode(Some(1), Some(0)).game_mode_state(Some("062410")), Some(false));
}

#[test]
fn game_mode_062810_uses_byte_3() {
    assert_eq!(game_mode(Some(0), Some(1)).game_mode_state(Some("062810")), Some(true));
    assert_eq!(game_mode(Some(1), Some(0)).game_mode_state(Some("062810")), Some(false));
}

#[test]
fn game_mode_063010_uses_byte_3() {
    assert_eq!(game_mode(Some(0), Some(1)).game_mode_state(Some("063010")), Some(true));
    assert_eq!(game_mode(Some(1), Some(0)).game_mode_state(Some("063010")), Some(false));
}

#[test]
fn game_mode_063410_uses_byte_3() {
    assert_eq!(game_mode(Some(0), Some(1)).game_mode_state(Some("063410")), Some(true));
    assert_eq!(game_mode(Some(1), Some(0)).game_mode_state(Some("063410")), Some(false));
}

#[test]
fn game_mode_another_model_uses_byte_1() {
    assert_eq!(game_mode(Some(1), Some(0)).game_mode_state(Some("065414")), Some(true));
    assert_eq!(game_mode(Some(0), Some(1)).game_mode_state(Some("065414")), Some(false));
    assert_eq!(game_mode(Some(1), None).game_mode_state(Some("065414")), Some(true));
}

#[test]
fn game_mode_missing_chosen_byte_is_none() {
    assert_eq!(game_mode(Some(1), None).game_mode_state(Some("062410")), None);
    assert_eq!(game_mode(None, Some(1)).game_mode_state(Some("065414")), None);
}

#[test]
fn game_mode_non_zero_is_on() {
    assert_eq!(game_mode(Some(0), Some(2)).game_mode_state(Some("062410")), Some(true));
    assert_eq!(game_mode(Some(2), Some(0)).game_mode_state(Some("065414")), Some(true));
}

#[test]
fn game_mode_from_the_captured_push() {
    // gamemode-02: state 1 on byte 1.
    assert_eq!(decode_frame("AA 09 00 00 04 02 FF 02 00 05 01"), Some(game_mode(Some(1), None)));
    let Some(m) = decode_frame("AA 09 00 00 04 02 FF 02 00 05 01") else { panic!() };
    assert_eq!(m.game_mode_state(Some("065414")), Some(true));
    assert_eq!(m.game_mode_state(Some("062410")), None);
}
