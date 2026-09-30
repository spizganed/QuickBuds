//! RFCOMM spike: find the paired buds, connect to the OPPO SPP service, run the start of the
//! init sequence (PROTOCOL.md "Init sequence") and print every packet plus the battery.
//! Usage: `quickbuds [name filter]`. Windows only for now; Linux (BlueZ) comes next.

mod protocol;

use protocol::*;
use std::time::{Duration, Instant};

/// 079A first: the one Buds 4 answers. 1107 for other models.
const SPP_UUIDS: [u128; 2] = [
    0x0000079A_D102_11E1_9B23_00025B00A5A5,
    0x00001107_D102_11E1_9B23_00025B00A5A5,
];

fn hex(b: &[u8]) -> String { b.iter().map(|x| format!("{x:02X}")).collect::<Vec<_>>().join(" ") }

fn main() {
    let filter = std::env::args().nth(1).unwrap_or_default().to_lowercase();
    let mut link = match bt::open(&filter) {
        Ok(l) => l,
        Err(e) => { eprintln!("{e}"); std::process::exit(1) }
    };

    let init: [(u16, u8, &[u8]); 5] = [
        (CMD_HANDSHAKE, 1, &[]),
        (CMD_QUERY_PRODUCT_ID, 2, &[]),
        (CMD_QUERY_BROADCAST, 3, &[]),
        (CMD_REGISTER_NOTIFY, 4, &[3, 1, 2, 3]), // count first, never shorter (PROTOCOL.md)
        (CMD_QUERY_BATTERY, 0xF0, &[]),
    ];
    let mut framer = Framer::default();
    let mut buf = [0u8; 1024];
    let mut read_for = |link: &mut bt::Link, ms: u64| {
        let end = Instant::now() + Duration::from_millis(ms);
        while Instant::now() < end {
            let n = match link.read(&mut buf) {
                Ok(n) => n,
                Err(e) => { eprintln!("Connection lost: {e}"); std::process::exit(2) }
            };
            for p in framer.push(&buf[..n]) {
                let cmd = cmd_of(&p);
                println!("RX {cmd:04X}: {}", hex(&p));
                if cmd == CMD_QUERY_BATTERY | REPLY || (cmd == EVT_PUSH && payload_of(&p).first() == Some(&1)) {
                    let pl = payload_of(&p);
                    // The reply starts with a status byte; the push with its subtype `01`.
                    for (i, level, charging) in battery(&pl[1..]) {
                        let who = ["?", "left", "right", "case"].get(i as usize).unwrap_or(&"?");
                        println!("  battery {who}: {level}%{}", if charging { " charging" } else { "" });
                    }
                }
            }
        }
    };

    read_for(&mut link, 300);
    for (cmd, seq, payload) in init {
        let p = build_packet(cmd, seq, payload);
        println!("TX {cmd:04X}: {}", hex(&p));
        if let Err(e) = link.write(&p) { eprintln!("write failed: {e}"); std::process::exit(2) }
        read_for(&mut link, 200);
    }
    println!("Listening for pushes (Ctrl+C to stop)...");
    loop { read_for(&mut link, 60_000); }
}

#[cfg(windows)]
mod bt {
    //! Winsock Bluetooth: built into Windows, no driver or service needed.
    use super::SPP_UUIDS;
    use std::mem::{size_of, zeroed};
    use windows_sys::core::GUID;
    use windows_sys::Win32::Devices::Bluetooth::*;
    use windows_sys::Win32::Networking::WinSock::*;

    pub struct Link(SOCKET);

    impl Drop for Link {
        fn drop(&mut self) { unsafe { closesocket(self.0); } }
    }

    fn err(what: &str) -> String { format!("{what}: WSA error {}", unsafe { WSAGetLastError() }) }

    impl Link {
        /// Ok(0) on timeout (200 ms), Err when the link is gone.
        pub fn read(&mut self, buf: &mut [u8]) -> Result<usize, String> {
            let n = unsafe { recv(self.0, buf.as_mut_ptr(), buf.len() as i32, 0) };
            if n > 0 { return Ok(n as usize); }
            if n == 0 { return Err("closed by the buds".into()); }
            if unsafe { WSAGetLastError() } == WSAETIMEDOUT { Ok(0) } else { Err(err("recv")) }
        }

        pub fn write(&mut self, data: &[u8]) -> Result<(), String> {
            let n = unsafe { send(self.0, data.as_ptr(), data.len() as i32, 0) };
            if n == data.len() as i32 { Ok(()) } else { Err(err("send")) }
        }
    }

    /// Paired devices, connected ones first: (address, name).
    fn paired() -> Vec<(u64, String, bool)> {
        let mut out = Vec::new();
        unsafe {
            let mut params: BLUETOOTH_DEVICE_SEARCH_PARAMS = zeroed();
            params.dwSize = size_of::<BLUETOOTH_DEVICE_SEARCH_PARAMS>() as u32;
            params.fReturnAuthenticated = 1;
            params.fReturnRemembered = 1;
            params.fReturnConnected = 1;
            let mut info: BLUETOOTH_DEVICE_INFO = zeroed();
            info.dwSize = size_of::<BLUETOOTH_DEVICE_INFO>() as u32;
            let h = BluetoothFindFirstDevice(&params, &mut info);
            if h.is_null() { return out; }
            loop {
                let name = &info.szName;
                let len = name.iter().position(|&c| c == 0).unwrap_or(name.len());
                out.push((info.Address.Anonymous.ullLong, String::from_utf16_lossy(&name[..len]), info.fConnected != 0));
                if BluetoothFindNextDevice(h, &mut info) == 0 { break; }
            }
            BluetoothFindDeviceClose(h);
        }
        out.sort_by_key(|d| !d.2);
        out
    }

    fn connect(addr: u64, uuid: u128) -> Result<Link, String> {
        unsafe {
            let s = socket(AF_BTH as i32, SOCK_STREAM, BTHPROTO_RFCOMM as i32);
            if s == INVALID_SOCKET { return Err(err("socket")); }
            let link = Link(s);
            let mut sa: SOCKADDR_BTH = zeroed();
            sa.addressFamily = AF_BTH;
            sa.btAddr = addr;
            sa.serviceClassId = GUID::from_u128(uuid); // port 0: Windows looks the channel up over SDP
            if windows_sys::Win32::Networking::WinSock::connect(s, &sa as *const _ as *const SOCKADDR, size_of::<SOCKADDR_BTH>() as i32) != 0 {
                return Err(err("connect"));
            }
            let timeout: u32 = 200;
            setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &timeout as *const _ as *const u8, 4);
            Ok(link)
        }
    }

    pub fn open(filter: &str) -> Result<Link, String> {
        unsafe {
            let mut wsa: WSADATA = zeroed();
            if WSAStartup(0x0202, &mut wsa) != 0 { return Err(err("WSAStartup")); }
        }
        let devices = paired();
        if devices.is_empty() { return Err("No paired Bluetooth devices. Pair the buds in Windows settings first.".into()); }
        for (addr, name, connected) in devices {
            if !filter.is_empty() && !name.to_lowercase().contains(filter) { continue; }
            println!("{name} ({addr:012X}){}", if connected { " connected" } else { "" });
            for uuid in SPP_UUIDS {
                match connect(addr, uuid) {
                    Ok(l) => { println!("  Connected via {uuid:032X}"); return Ok(l); }
                    Err(e) => println!("  {uuid:032X}: {e}"),
                }
            }
        }
        Err("No device answered on the QuickBuds services. Is the phone app still holding the link?".into())
    }
}

#[cfg(not(windows))]
mod bt {
    pub struct Link;
    impl Link {
        pub fn read(&mut self, _: &mut [u8]) -> Result<usize, String> { unreachable!() }
        pub fn write(&mut self, _: &[u8]) -> Result<(), String> { unreachable!() }
    }
    pub fn open(_: &str) -> Result<Link, String> { Err("Linux (BlueZ) is not in the spike yet.".into()) }
}
