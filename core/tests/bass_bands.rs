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

/// Pushes after a change (realme Buds T500 Pro capture, 2026-11-13).
#[test]
fn band_push() {
    let push = |payload: &[u8]| decode(&Message { cmd: 0x0508, seq: 0xFF, payload: payload.to_vec() });
    assert_eq!(push(&[0xFB, 0x05, 0xFF, 0x02]), Some(Inbound::BassBands(vec![(2, -1)])));
    assert_eq!(push(&[0xFB, 0x05, 0xFF]), None);
}

#[test]
fn custom_list_push() {
    let mut p = vec![0x01, 0x01, 0xFA, 0x06, 0x04, 0x03];
    p.extend_from_slice(b"one");
    p.extend_from_slice(&[0x06, 0x3E, 0x00, 0x06, 0xFA, 0x00, 0xFA, 0xE8, 0x03, 0x01, 0xA0, 0x0F, 0xFE, 0x40, 0x1F, 0x03, 0x80, 0x3E, 0xFC]);
    let Some(Inbound::EqCustom(list)) = decode(&Message { cmd: 0x0506, seq: 0xFF, payload: p }) else { panic!("no list") };
    assert_eq!((list[0].id, list[0].name.as_str(), list[0].selected), (4, "one", true));
}
