//! QuickBuds core: no I/O, no dependencies. The platform shells own the Bluetooth socket.
pub mod cmd;
pub mod feature_sync;
mod json;
pub mod msg;
pub mod wire;
pub mod ffi;
