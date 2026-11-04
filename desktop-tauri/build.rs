//! Strings and icons from the phone app's resources (`desktop/res_build.rs`), then Tauri's own build step.

include!("../desktop/res_build.rs");

fn main() {
    generate();
    tauri_build::build();
}
