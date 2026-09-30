//! Wire format (docs/PROTOCOL.md §2): `AA <TotalLen LEB128> 00 00 <cmd lo> <cmd hi> <seq> <len lo> <len hi> <payload>`.
//! TotalLen counts everything after itself: 7 + payload.

pub const CMD_HANDSHAKE: u16 = 0x0100;
pub const CMD_QUERY_PRODUCT_ID: u16 = 0x0103;
pub const CMD_QUERY_BATTERY: u16 = 0x0106;
pub const CMD_QUERY_BROADCAST: u16 = 0x0200;
pub const CMD_REGISTER_NOTIFY: u16 = 0x0205;
/// Replies set the high bit of the command's high byte (`0x0106` -> `0x8106`).
pub const REPLY: u16 = 0x8000;
pub const EVT_PUSH: u16 = 0x0204;

pub fn build_packet(cmd: u16, seq: u8, payload: &[u8]) -> Vec<u8> {
    let mut p = vec![0xAA];
    let mut total = 7 + payload.len();
    loop {
        let b = (total & 0x7F) as u8;
        total >>= 7;
        p.push(if total != 0 { b | 0x80 } else { b });
        if total == 0 { break; }
    }
    let n = payload.len();
    p.extend_from_slice(&[0, 0, cmd as u8, (cmd >> 8) as u8, seq, n as u8, (n >> 8) as u8]);
    p.extend_from_slice(payload);
    p
}

/// (TotalLen, bytes it took), or None while incomplete.
fn leb128(b: &[u8]) -> Option<(usize, usize)> {
    let mut v = 0usize;
    for (i, &x) in b.iter().enumerate().take(3) {
        v |= ((x & 0x7F) as usize) << (7 * i);
        if x & 0x80 == 0 { return Some((v, i + 1)); }
    }
    None
}

fn header_len(p: &[u8]) -> usize { 1 + leb128(&p[1..]).map_or(1, |(_, n)| n) + 7 }

pub fn cmd_of(p: &[u8]) -> u16 {
    let h = header_len(p) - 7;
    u16::from_le_bytes([p[h + 2], p[h + 3]])
}

pub fn payload_of(p: &[u8]) -> &[u8] { &p[header_len(p)..] }

/// Splits the RFCOMM byte stream into packets. Bytes before an `AA` are dropped (and counted).
#[derive(Default)]
pub struct Framer { buf: Vec<u8>, pub discarded: usize }

impl Framer {
    pub fn push(&mut self, data: &[u8]) -> Vec<Vec<u8>> {
        self.buf.extend_from_slice(data);
        let mut out = Vec::new();
        loop {
            match self.buf.iter().position(|&b| b == 0xAA) {
                Some(i) => { self.discarded += i; self.buf.drain(..i); }
                None => { self.discarded += self.buf.len(); self.buf.clear(); break; }
            }
            let Some((total, n)) = leb128(&self.buf[1..]) else { break };
            // ponytail: 4 KiB sanity cap; a bogus length resyncs on the next AA.
            if total < 7 || total > 4096 { self.buf.remove(0); self.discarded += 1; continue; }
            let size = 1 + n + total;
            if self.buf.len() < size { break; }
            out.push(self.buf.drain(..size).collect());
        }
        out
    }
}

/// Battery (§7): `<count>` then `<index> <raw>` pairs, 1 left, 2 right, 3 case; level `raw & 7F`, charging `raw & 80`.
pub fn battery(payload: &[u8]) -> Vec<(u8, u8, bool)> {
    let Some((&count, rest)) = payload.split_first() else { return Vec::new() };
    rest.chunks_exact(2).take(count as usize).map(|c| (c[0], c[1] & 0x7F, c[1] & 0x80 != 0)).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn packet_roundtrip() {
        let p = build_packet(CMD_REGISTER_NOTIFY, 5, &[3, 1, 2, 3]);
        assert_eq!(p, [0xAA, 11, 0, 0, 0x05, 0x02, 5, 4, 0, 3, 1, 2, 3]);
        assert_eq!(cmd_of(&p), 0x0205);
        assert_eq!(payload_of(&p), [3, 1, 2, 3]);
        // Two-byte LEB128 length.
        let big = build_packet(0x0401, 1, &[0; 200]);
        assert_eq!(&big[..3], [0xAA, 0xCF, 0x01]);
        assert_eq!(payload_of(&big).len(), 200);
    }

    #[test]
    fn framer_splits_and_resyncs() {
        let a = build_packet(0x8106, 0xF0, &[3, 1, 0x64, 2, 0x64, 3, 0xD0]);
        let mut stream = vec![0x01, 0x02];
        stream.extend(&a);
        stream.extend(&a);
        let mut f = Framer::default();
        let (x, y) = stream.split_at(7);
        assert!(f.push(x).is_empty());
        let got = f.push(y);
        assert_eq!(got, vec![a.clone(), a]);
        assert_eq!(f.discarded, 2);
        assert_eq!(battery(payload_of(&got[0])), [(1, 100, false), (2, 100, false), (3, 80, true)]);
    }
}
