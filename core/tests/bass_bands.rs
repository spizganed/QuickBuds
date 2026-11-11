//! Decode of the `0x812C` bass-band reply (realme Buds T310 capture, 2026-11-09).

use buds_core::msg::{decode, Inbound};
use buds_core::wire::Message;

fn reply(payload: &[u8]) -> Option<Inbound> {
    decode(&Message { cmd: 0x812C, seq: 0x11, payload: payload.to_vec() })
}

#[test]
fn three_bands() {
    let p = [0x00, 0x03, 0xFB, 0x05, 0x03, 0x01, 0xFB, 0x05, 0xFD, 0x02, 0xFB, 0x05, 0x02, 0x03];
    assert_eq!(reply(&p), Some(Inbound::BassBands(vec![(1, 3), (2, -3), (3, 2)])));
}

#[test]
fn short_or_refused() {
    assert_eq!(reply(&[0x00, 0x02, 0xFB, 0x05, 0x03, 0x01]), None);
    assert_eq!(reply(&[0x01, 0x01, 0xFB, 0x05, 0x03, 0x01]), None);
}
