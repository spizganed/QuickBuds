//! C ABI for a native shell: bytes in, bytes out, no state kept between calls. Nothing calls it yet; the spike probes prove a round trip.
use crate::wire::{Message, Reassembler};

/// Feeds `len` bytes to a reassembler and returns how many whole frames it held. Spike probe: proves a call round trip.
#[no_mangle]
pub extern "C" fn buds_frame_count(data: *const u8, len: usize) -> i32 {
    if data.is_null() { return -1; }
    let bytes = unsafe { std::slice::from_raw_parts(data, len) };
    Reassembler::default().push(bytes).len() as i32
}

/// Decodes one whole frame: writes the command to `cmd` and returns the payload length, or -1 when it is not a frame.
#[no_mangle]
pub extern "C" fn buds_frame_cmd(data: *const u8, len: usize, cmd: *mut u16) -> i32 {
    if data.is_null() || cmd.is_null() { return -1; }
    let bytes = unsafe { std::slice::from_raw_parts(data, len) };
    match Reassembler::default().push(bytes).first() {
        Some(Message { cmd: c, payload, .. }) => { unsafe { *cmd = *c }; payload.len() as i32 }
        None => -1,
    }
}

/// Decodes one frame into a typed message and returns its `Debug` text length written to `out` (up to `cap` bytes); -1: no frame
/// or no known message. Spike probe: it keeps the whole decoder, the reply builder and the JSON check in the library, so the size is honest.
#[no_mangle]
pub extern "C" fn buds_decode_debug(data: *const u8, len: usize, out: *mut u8, cap: usize) -> i32 {
    if data.is_null() || out.is_null() { return -1; }
    let bytes = unsafe { std::slice::from_raw_parts(data, len) };
    let Some(m) = Reassembler::default().push(bytes).into_iter().next() else { return -1 };
    let text = match crate::msg::decode(&m) {
        Some(i) => format!("{i:?}"),
        None => match crate::msg::answer(&m, 0) { Some(a) => format!("{a:?}"), None => return -1 },
    };
    let n = text.len().min(cap);
    unsafe { std::ptr::copy_nonoverlapping(text.as_ptr(), out, n) };
    n as i32
}

#[cfg(test)]
mod tests {
    #[test]
    fn decode_probe_and_timing() {
        // A battery push frame is not needed: any whole frame must come back as a count of 1.
        let f = crate::wire::encode(&crate::wire::Message { cmd: 0x0100, seq: 1, payload: vec![] }, crate::wire::BUDGET).remove(0);
        assert_eq!(super::buds_frame_count(f.as_ptr(), f.len()), 1);
        let mut cmd = 0u16;
        assert_eq!(super::buds_frame_cmd(f.as_ptr(), f.len(), &mut cmd), 0);
        assert_eq!(cmd, 0x0100);
        let t = std::time::Instant::now();
        for _ in 0..100_000 { super::buds_frame_count(f.as_ptr(), f.len()); }
        eprintln!("buds_frame_count: {} ns per call", t.elapsed().as_nanos() / 100_000);
    }
}
