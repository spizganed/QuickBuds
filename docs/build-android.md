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

The Android app has no unit tests. Run `python3 scripts/check-locales.py`, then test on real buds.
