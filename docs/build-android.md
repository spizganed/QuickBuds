# Build the Android app

You need JDK 21 and the Android SDK with platform 37. Android Studio brings both. Its bundled JBR
works as `JAVA_HOME`.

```bash
git clone https://github.com/spizganed/QuickBuds.git
cd QuickBuds
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # your SDK path
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- The first build downloads Gradle through the wrapper. The only library is `androidx.core`.
- **A debug build cannot install over the release app.** The signing keys differ. Uninstall the
  release first, and uninstall the debug build before you go back.
- `./gradlew assembleRelease` without the release key builds an unsigned APK. That is expected.

Versions: Gradle 9.6.0, AGP 9.4.0, Kotlin 2.4.0, `compileSdk` / `targetSdk` 37, `minSdk` 26. AGP 9
compiles Kotlin itself. Do not add the `org.jetbrains.kotlin.android` plugin.

Run `./gradlew :app:testDebugUnitTest` and `python3 scripts/check-locales.py`, then test on real buds.

## Build on a phone (Termux)

Gradle's `aapt2` is a glibc binary and does not run on Android. Use the Termux one:

```sh
pkg install aapt2 apksigner android-tools
echo "android.aapt2FromMavenOverride=$PREFIX/bin/aapt2" >> ~/.gradle/gradle.properties
sh ./gradlew assembleRelease
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out qb.apk app/build/outputs/apk/release/app-release-unsigned.apk
adb install -r qb.apk
```

If `aapt2` stops with "cannot locate symbol", update the libraries it needs: `pkg install libprotobuf abseil-cpp fmt`.
The debug key differs from the release key: uninstall the release app first, as above. The desktop app builds in CI.
