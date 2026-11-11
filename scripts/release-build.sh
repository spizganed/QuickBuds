#!/usr/bin/env bash
# The Build workflow on this PC, many times faster than GitHub Actions: checks the locales, runs the Android,
# core and desktop tests and Android lint, builds the signed APK and AAB and the desktop files.
# Every release file lands in local/release/<version>/ (git-ignored). GitHub Actions stays for builds from the phone.
# Usage: scripts/release-build.sh
# Needs: the release key in local/keys/, and what scripts/desktop-dist.sh needs.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
ver=$(sed -n 's/.*versionName = "\(.*\)"/\1/p' app/build.gradle.kts)
dver=$(sed -n 's/^version = "\(.*\)"/\1/p' desktop-tauri/Cargo.toml | head -1)
out=local/release/$ver
mkdir -p "$out"

# A failed check stops the release and names the check.
check() {
    local name=$1
    shift
    echo "== $name"
    "$@" || { echo "FAILED: $name" >&2; exit 1; }
}

check "locales" python3 scripts/check-locales.py
check "Android unit tests and lint" ./gradlew :app:testDebugUnitTest :app:lintDebug
check "core tests" sh -c 'cd core && cargo test'
check "desktop tests" sh -c 'cd desktop-tauri && CARGO_TARGET_DIR=target/test cargo test -q'

# The desktop files build in the background while Gradle builds the APK and the AAB.
log=$(mktemp -d)
scripts/desktop-dist.sh "$dver" >"$log/dist.txt" 2>&1 & dpid=$!
fail=0
./gradlew -q assembleRelease bundleRelease || { fail=1; echo "FAILED: release APK and AAB" >&2; }
wait $dpid || { fail=1; echo "FAILED: desktop files" >&2; cat "$log/dist.txt"; }
[ $fail = 0 ] || exit 1
cp app/build/outputs/apk/release/app-release.apk "$out/QuickBuds$ver.apk"
cp app/build/outputs/bundle/release/app-release.aab "$out/QuickBuds$ver.aab"
cp desktop-tauri/dist/* "$out/"
# The updater and the Linux installer verify the download against this file.
( cd "$out" && sha256sum QuickBuds*.apk QuickBuds*.aab QuickBuds*.zip QuickBuds*.AppImage > SHA256SUMS )
ls -la "$out"
