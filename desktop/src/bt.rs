//! RFCOMM link to the buds. Windows: Winsock Bluetooth, built into Windows (no driver, no service).
//! Linux: BlueZ (D-Bus for the paired list, kernel RFCOMM socket, channel from the buds' SDP record).
//! Dev: `QB_BRIDGE=127.0.0.1:7979` uses the Android app's RFCOMM bridge (Dev tools › Bridge) instead,
//! for running this app on the phone itself. It connects on a user Connect only.

use std::io::{ErrorKind, Read, Write};
use std::net::TcpStream;
use std::time::Duration;

/// 079A first: the one Buds 4 answers. 1107 for other models.
const SPP_UUIDS: [u128; 2] = [
    0x0000079A_D102_11E1_9B23_00025B00A5A5,
    0x00001107_D102_11E1_9B23_00025B00A5A5,
];

pub struct Device {
    pub addr: u64,
    pub name: String,
    /// The system has an ACL link to it (audio connected).
    pub connected: bool,
    /// It offers one of [SPP_UUIDS] (the system's cached service list): the vendor's control service.
    pub vendor: bool,
}

pub enum Link {
    Bt(imp::Link),
    Bridge(TcpStream),
}

impl Link {
    /// Ok(0) on timeout (50 ms), Err when the link is gone.
    pub fn read(&mut self, buf: &mut [u8]) -> Result<usize, String> {
        match self {
            Link::Bt(l) => l.read(buf),
            Link::Bridge(s) => match s.read(buf) {
                Ok(0) => Err("bridge closed".into()),
                Ok(n) => Ok(n),
                Err(e) if matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => Ok(0),
                Err(e) => Err(format!("bridge: {e}")),
            },
        }
    }

    pub fn write(&mut self, data: &[u8]) -> Result<(), String> {
        match self {
            Link::Bt(l) => l.write(data),
            Link::Bridge(s) => s.write_all(data).map_err(|e| format!("bridge: {e}")),
        }
    }
}

fn bridge() -> Option<String> { std::env::var("QB_BRIDGE").ok() }

/// Devices paired with the system; with `QB_BRIDGE`, only the bridge.
pub fn paired() -> Vec<Device> {
    match bridge() {
        Some(a) => vec![Device { addr: 0, name: format!("Bridge {a}"), connected: true, vendor: true }],
        None => imp::paired(),
    }
}

pub fn connect(addr: u64) -> Result<Link, String> {
    let Some(a) = bridge() else { return imp::connect(addr).map(Link::Bt) };
    let s = TcpStream::connect(&a).map_err(|e| format!("bridge {a}: {e}"))?;
    s.set_read_timeout(Some(Duration::from_millis(50))).map_err(|e| e.to_string())?;
    s.set_nodelay(true).map_err(|e| e.to_string())?;
    Ok(Link::Bridge(s))
}

#[cfg(windows)]
mod imp {
    use super::{Device, SPP_UUIDS};
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
        /// Ok(0) on timeout (50 ms), Err when the link is gone.
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

    /// Devices paired with Windows.
    pub fn paired() -> Vec<Device> {
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
                // The services Windows set up for it; a vendor service it has no driver for may be missing,
                // so a device that answered once is also remembered (`session`).
                let mut guids: [GUID; 32] = zeroed();
                let mut count = guids.len() as u32;
                let vendor = BluetoothEnumerateInstalledServices(std::ptr::null_mut(), &info, &mut count, guids.as_mut_ptr()) == 0
                    && guids[..count.min(32) as usize].iter().any(|g| SPP_UUIDS.contains(&guid_u128(g)));
                out.push(Device {
                    addr: info.Address.Anonymous.ullLong,
                    name: String::from_utf16_lossy(&name[..len]),
                    connected: info.fConnected != 0,
                    vendor,
                });
                if BluetoothFindNextDevice(h, &mut info) == 0 { break; }
            }
            BluetoothFindDeviceClose(h);
        }
        out
    }

    fn guid_u128(g: &GUID) -> u128 {
        (g.data1 as u128) << 96 | (g.data2 as u128) << 80 | (g.data3 as u128) << 64 | u64::from_be_bytes(g.data4) as u128
    }

    fn connect_uuid(addr: u64, uuid: u128) -> Result<Link, String> {
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
            let timeout: u32 = 50;
            setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &timeout as *const _ as *const u8, 4);
            Ok(link)
        }
    }

    pub fn connect(addr: u64) -> Result<Link, String> {
        static WSA: std::sync::Once = std::sync::Once::new();
        WSA.call_once(|| unsafe {
            let mut wsa: WSADATA = zeroed();
            WSAStartup(0x0202, &mut wsa);
        });
        let mut last = String::new();
        for uuid in SPP_UUIDS {
            match connect_uuid(addr, uuid) {
                Ok(l) => return Ok(l),
                Err(e) => last = e,
            }
        }
        Err(last)
    }
}

/// Linux: BlueZ's D-Bus API for the paired list, kernel sockets for the rest. The RFCOMM channel comes
/// from the buds' own SDP record (one ServiceSearchAttribute request over L2CAP), so nothing is
/// registered with bluetoothd and no daemon or async runtime is involved.
#[cfg(not(windows))]
mod imp {
    use super::{Device, SPP_UUIDS};
    use dbus::arg::{prop_cast, PropMap};
    use dbus::blocking::stdintf::org_freedesktop_dbus::ObjectManager;
    use std::mem::size_of;
    use std::time::Duration;

    const AF_BLUETOOTH: i32 = 31;
    const BTPROTO_L2CAP: i32 = 0;
    const BTPROTO_RFCOMM: i32 = 3;
    const SDP_PSM: u16 = 1;

    #[repr(C)]
    struct SockaddrRc { family: u16, bdaddr: [u8; 6], channel: u8 }

    #[repr(C)]
    struct SockaddrL2 { family: u16, psm: u16, bdaddr: [u8; 6], cid: u16, bdaddr_type: u8 }

    pub struct Link(i32);

    impl Drop for Link {
        fn drop(&mut self) { unsafe { libc::close(self.0); } }
    }

    fn err(what: &str) -> String { format!("{what}: {}", std::io::Error::last_os_error()) }

    impl Link {
        /// Ok(0) on timeout (50 ms), Err when the link is gone.
        pub fn read(&mut self, buf: &mut [u8]) -> Result<usize, String> {
            let n = unsafe { libc::recv(self.0, buf.as_mut_ptr().cast(), buf.len(), 0) };
            if n > 0 { return Ok(n as usize); }
            if n == 0 { return Err("closed by the buds".into()); }
            match std::io::Error::last_os_error().raw_os_error() {
                Some(libc::EAGAIN) | Some(libc::EINTR) => Ok(0),
                _ => Err(err("recv")),
            }
        }

        pub fn write(&mut self, data: &[u8]) -> Result<(), String> {
            let n = unsafe { libc::send(self.0, data.as_ptr().cast(), data.len(), libc::MSG_NOSIGNAL) };
            if n == data.len() as isize { Ok(()) } else { Err(err("send")) }
        }
    }

    /// Devices paired with BlueZ (any adapter).
    pub fn paired() -> Vec<Device> {
        let Ok(conn) = dbus::blocking::Connection::new_system() else { return Vec::new() };
        let proxy = conn.with_proxy("org.bluez", "/", Duration::from_secs(2));
        let Ok(objects) = proxy.get_managed_objects() else { return Vec::new() };
        let mut out = Vec::new();
        for ifaces in objects.values() {
            let Some(dev) = ifaces.get("org.bluez.Device1") else { continue };
            if !flag(dev, "Paired") { continue; }
            let Some(addr) = prop_cast::<String>(dev, "Address").and_then(|a| parse_addr(a)) else { continue };
            let name = prop_cast::<String>(dev, "Alias").or_else(|| prop_cast(dev, "Name")).cloned().unwrap_or_default();
            // BlueZ's cached SDP result: the services the device offered when it was paired.
            let vendor = prop_cast::<Vec<String>>(dev, "UUIDs").is_some_and(|u| u.iter().any(|u| is_vendor_uuid(u)));
            out.push(Device { addr, name, connected: flag(dev, "Connected"), vendor });
        }
        out
    }

    /// "0000079a-d102-11e1-9b23-00025b00a5a5" (BlueZ's form, any case) is one of [SPP_UUIDS].
    fn is_vendor_uuid(s: &str) -> bool {
        let hex: String = s.chars().filter(|c| c.is_ascii_hexdigit()).collect();
        u128::from_str_radix(&hex, 16).is_ok_and(|u| SPP_UUIDS.contains(&u))
    }

    fn flag(dev: &PropMap, key: &str) -> bool { prop_cast::<bool>(dev, key).copied().unwrap_or(false) }

    /// "A8:E6:E8:92:C1:25" -> 0xA8E6E892C125, the same number Windows uses.
    fn parse_addr(s: &str) -> Option<u64> {
        let hex: String = s.split(':').collect();
        if hex.len() != 12 { return None; }
        u64::from_str_radix(&hex, 16).ok()
    }

    /// bdaddr_t is little-endian: the last byte of the printed address first.
    fn bdaddr(addr: u64) -> [u8; 6] {
        let b = addr.to_le_bytes();
        [b[0], b[1], b[2], b[3], b[4], b[5]]
    }

    fn set_timeout(fd: i32, ms: i64) {
        let tv = libc::timeval { tv_sec: ms / 1000, tv_usec: (ms % 1000) * 1000 };
        let p = &tv as *const libc::timeval as *const libc::c_void;
        let len = size_of::<libc::timeval>() as libc::socklen_t;
        unsafe {
            libc::setsockopt(fd, libc::SOL_SOCKET, libc::SO_RCVTIMEO, p, len);
            libc::setsockopt(fd, libc::SOL_SOCKET, libc::SO_SNDTIMEO, p, len);
        }
    }

    /// The RFCOMM channel of the service `uuid`, from the buds' SDP server.
    fn sdp_channel(addr: u64, uuid: u128) -> Result<u8, String> {
        let fd = unsafe { libc::socket(AF_BLUETOOTH, libc::SOCK_SEQPACKET, BTPROTO_L2CAP) };
        if fd < 0 { return Err(err("sdp socket")); }
        let _sock = Link(fd); // closes it on return
        set_timeout(fd, 5000);
        let sa = SockaddrL2 { family: AF_BLUETOOTH as u16, psm: SDP_PSM.to_le(), bdaddr: bdaddr(addr), cid: 0, bdaddr_type: 0 };
        if unsafe { libc::connect(fd, &sa as *const _ as *const libc::sockaddr, size_of::<SockaddrL2>() as u32) } != 0 {
            return Err(err("sdp connect"));
        }
        // ServiceSearchAttributeRequest: pattern = [uuid128], max bytes, attributes = [0x0004
        // ProtocolDescriptorList]; repeated with the continuation state until the server sends none.
        let mut attrs = Vec::new();
        let mut cont: Vec<u8> = vec![0];
        for tid in 1u16..=16 {
            let mut p = vec![0x35, 17, 0x1C];
            p.extend_from_slice(&uuid.to_be_bytes());
            p.extend_from_slice(&[0xFF, 0xFF, 0x35, 0x03, 0x09, 0x00, 0x04]);
            p.extend_from_slice(&cont);
            let mut pdu = vec![0x06];
            pdu.extend_from_slice(&tid.to_be_bytes());
            pdu.extend_from_slice(&(p.len() as u16).to_be_bytes());
            pdu.extend_from_slice(&p);
            if unsafe { libc::send(fd, pdu.as_ptr().cast(), pdu.len(), libc::MSG_NOSIGNAL) } != pdu.len() as isize {
                return Err(err("sdp send"));
            }
            let mut buf = [0u8; 1024];
            let n = unsafe { libc::recv(fd, buf.as_mut_ptr().cast(), buf.len(), 0) };
            if n < 7 { return Err(if n < 0 { err("sdp recv") } else { "sdp: short reply".into() }); }
            let r = &buf[..n as usize];
            if r[0] != 0x07 { return Err(format!("sdp: error reply {:02X?}", r)); }
            let count = u16::from_be_bytes([r[5], r[6]]) as usize;
            let Some(chunk) = r.get(7..7 + count) else { return Err("sdp: bad length".into()) };
            attrs.extend_from_slice(chunk);
            let rest = &r[7 + count..];
            if rest.is_empty() || rest[0] == 0 { break; }
            cont = rest.to_vec();
        }
        // ProtocolDescriptorList holds ( RFCOMM uuid16 0x0003, uint8 channel ).
        attrs.windows(5).find(|w| w[..4] == [0x19, 0x00, 0x03, 0x08]).map(|w| w[4])
            .ok_or_else(|| format!("no RFCOMM service {uuid:032X}"))
    }

    fn connect_uuid(addr: u64, uuid: u128) -> Result<Link, String> {
        let channel = sdp_channel(addr, uuid)?;
        let fd = unsafe { libc::socket(AF_BLUETOOTH, libc::SOCK_STREAM, BTPROTO_RFCOMM) };
        if fd < 0 { return Err(err("socket")); }
        let link = Link(fd);
        let sa = SockaddrRc { family: AF_BLUETOOTH as u16, bdaddr: bdaddr(addr), channel };
        if unsafe { libc::connect(fd, &sa as *const _ as *const libc::sockaddr, size_of::<SockaddrRc>() as u32) } != 0 {
            return Err(err(&format!("connect channel {channel}")));
        }
        set_timeout(fd, 50);
        Ok(link)
    }

    pub fn connect(addr: u64) -> Result<Link, String> {
        let mut last = String::new();
        for uuid in SPP_UUIDS {
            match connect_uuid(addr, uuid) {
                Ok(l) => return Ok(l),
                Err(e) => last = e,
            }
        }
        Err(last)
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn vendor_uuids() {
            assert!(is_vendor_uuid("0000079a-d102-11e1-9b23-00025b00a5a5"));
            assert!(is_vendor_uuid("{00001107-D102-11E1-9B23-00025B00A5A5}"));
            assert!(!is_vendor_uuid("0000110b-0000-1000-8000-00805f9b34fb"));
        }

        #[test]
        fn address_round_trip() {
            let a = parse_addr("A8:E6:E8:92:C1:25").unwrap();
            assert_eq!(a, 0xA8E6E892C125);
            assert_eq!(bdaddr(a), [0x25, 0xC1, 0x92, 0xE8, 0xE6, 0xA8]);
        }

        /// Needs connected buds: `cargo test live_battery -- --ignored --nocapture`.
        #[test]
        #[ignore]
        fn live_battery() {
            let devices = paired();
            for d in &devices { println!("{:012X} {} connected={} vendor={}", d.addr, d.name, d.connected, d.vendor); }
            let d = devices.iter().find(|d| d.connected && d.vendor).expect("no connected buds");
            for uuid in SPP_UUIDS { println!("SDP {uuid:032X}: {:?}", sdp_channel(d.addr, uuid)); }
            let mut link = connect(d.addr).expect("connect");
            link.write(&[0xAA, 0x07, 0x00, 0x00, 0x06, 0x01, 0x01, 0x00, 0x00]).unwrap();
            let mut buf = [0u8; 256];
            for _ in 0..40 {
                let n = link.read(&mut buf).unwrap();
                if n > 0 { println!("RX {:02X?}", &buf[..n]); }
                if buf[..n].windows(2).any(|w| w == [0x06, 0x81]) { return; }
            }
            panic!("no battery reply (0x8106)");
        }
    }
}
