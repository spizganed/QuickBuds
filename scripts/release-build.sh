#!/usr/bin/env bash
# The Build workflow on this PC, many times faster than GitHub Actions: checks the locales, builds the signed APK
# and AAB, runs the desktop tests and builds the desktop archives. Every release file lands in
# local/release/<version>/ (git-ignored). GitHub Actions stays for builds from the phone.
# Usage: scripts/release-build.sh
# Needs: the release key in local/keys/, and what scripts/desktop-dist.sh needs.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
ver=$(sed -n 's/.*versionName = "\(.*\)"/\1/p' app/build.gradle.kts)
dver=$(sed -n 's/^version = "\(.*\)"/\1/p' desktop/Cargo.toml | head -1)
out=local/release/$ver
mkdir -p "$out"

python3 scripts/check-locales.py
./gradlew -q assembleRelease bundleRelease
cp app/build/outputs/apk/release/app-release.apk "$out/QuickBuds$ver.apk"
cp app/build/outputs/bundle/release/app-release.aab "$out/QuickBuds$ver.aab"

(cd desktop && cargo test -q)
scripts/desktop-dist.sh "$dver"
cp desktop/dist/* "$out/"
ls -la "$out"
