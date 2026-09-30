//! RFCOMM link to the buds. Windows: Winsock Bluetooth, built into Windows (no driver, no service).

/// 079A first: the one Buds 4 answers. 1107 for other models.
const SPP_UUIDS: [u128; 2] = [
    0x0000079A_D102_11E1_9B23_00025B00A5A5,
    0x00001107_D102_11E1_9B23_00025B00A5A5,
];

pub struct Device {
    pub addr: u64,
    pub name: String,
    /// Windows has an ACL link to it (audio connected).
    pub connected: bool,
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
                out.push(Device {
                    addr: info.Address.Anonymous.ullLong,
                    name: String::from_utf16_lossy(&name[..len]),
                    connected: info.fConnected != 0,
                });
                if BluetoothFindNextDevice(h, &mut info) == 0 { break; }
            }
            BluetoothFindDeviceClose(h);
        }
        out
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

#[cfg(not(windows))]
mod imp {
    // ponytail: Linux (BlueZ profile) lands after the VM spike.
    use super::Device;
    pub struct Link;
    impl Link {
        pub fn read(&mut self, _: &mut [u8]) -> Result<usize, String> { Err("unsupported".into()) }
        pub fn write(&mut self, _: &[u8]) -> Result<(), String> { Err("unsupported".into()) }
    }
    pub fn paired() -> Vec<Device> { Vec::new() }
    pub fn connect(_: u64) -> Result<Link, String> { Err("Linux is not supported yet".into()) }
}

pub use imp::*;
