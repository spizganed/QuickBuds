//! Strings and icons from the phone app's resources (`res_build.rs`), then Tauri's own build step.

include!("res_build.rs");

fn main() {
    generate();
    tauri_build::build();
}
