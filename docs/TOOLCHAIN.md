# Building on the phone

QuickBuds is built, installed and tested on the same Android phone it runs on. The phone is the
build machine and the test device at once. A PC is optional: it only opens an SSH session to the
phone for a bigger keyboard and screen. This page is the setup, for anyone who wants to work the
same way.

## Why it is built this way

The developer has no laptop, only a desktop at home. QuickBuds is worked on in free time at the day
job, on an office desktop where installing or downloading development tools is not an option (the
employer has no objection to the work itself). So the phone is the whole machine: Termux builds, adb
installs and tests, and the office PC only opens an SSH session using the ssh client that Windows 11's
default terminal already has. Nothing is installed on the PC.

## AI assistant

The code is written with [Claude Code](https://claude.com/claude-code) running inside Termux on the
phone, so the assistant builds, installs and reads logcat itself over the same adb link. The usual
setup is Claude Opus 5.5 at medium effort, with two plugins that keep it lean: **ponytail** (full:
the shortest solution that works, no speculative code) and **caveman** (lite: terse replies). The
rules the assistant follows live in [CLAUDE.md](../CLAUDE.md). Device testing and design decisions stay
with the developer; the assistant does protocol, parsers and code.

## The setup

A Nothing Phone (3a) on Android 16, [Termux](https://termux.dev) from F-Droid, the
earbuds paired to the phone. A full release build takes a few minutes.

## How it fits together

```
PC (any OS, headless is fine) --ssh :8022--> Termux on the phone
                                               ├─ git, gh, Gradle, JDK 21, Android SDK
                                               ├─ ./gradlew assembleRelease
                                               └─ adb (wireless debugging, to the phone itself)
                                                    ├─ install the APK
                                                    ├─ logcat, uiautomator, screenshots
                                                    └─ bugreport (Bluetooth HCI log)
```

## 1. Termux packages

```bash
pkg install git gh openjdk-21 aapt2 android-tools openssh python
```

`android-tools` gives `adb`. Termux's own `aapt2` is needed because the one Gradle downloads is an
x86 binary.

## 2. The Android SDK

Only the command-line tools, one platform and one build-tools version:

```bash
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
curl -O https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip commandlinetools-linux-13114758_latest.zip && mv cmdline-tools latest
termux-fix-shebang latest/bin/*
```

Use build **13114758**: newer command-line tools wrap an x86 binary that does not run on the phone.
`termux-fix-shebang` points the scripts at Termux's shell.

In `~/.bashrc`:

```bash
export JAVA_HOME=$PREFIX/lib/jvm/java-21-openjdk
export ANDROID_HOME=$HOME/android-sdk
```

Then:

```bash
~/android-sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-37.0" "build-tools;37.0.0"
ln -sf $PREFIX/bin/aapt2 ~/android-sdk/build-tools/37.0.0/aapt2
```

## 3. Gradle settings (outside the repo)

These only make sense on the phone, so they live in your home folder and the repo stays the same
for a PC build.

`~/.gradle/gradle.properties`:

```properties
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
org.gradle.configuration-cache=true
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
kotlin.daemon.jvmargs=-Xmx2g
```

`~/.gradle/init.d/quickbuds-phone.gradle.kts` moves the build output off shared storage (slow) and
skips release lint (slow on a phone; the PC build still runs it):

```kotlin
gradle.beforeProject {
    if (rootProject.name != "QuickBuds") return@beforeProject
    layout.buildDirectory.set(File(System.getProperty("user.home"), "qb-build/${path.replace(':', '_')}"))
    tasks.matching { it.name.startsWith("lintVital") }.configureEach { enabled = false }
}
```

## 4. Build

```bash
git clone https://github.com/spizganed/QuickBuds.git ~/projects/QuickBuds
cd ~/projects/QuickBuds
echo "sdk.dir=$HOME/android-sdk" > local.properties
./gradlew assembleRelease
```

The APK is `~/qb-build/_app/outputs/apk/release/app-release.apk`. "Unable to set daemon's environment
variables" from the Gradle daemon is harmless. If git reports every file as changed, run
`git config core.filemode false`.

## 5. Install to the phone itself

adb can talk to the phone it runs on, over Wireless debugging (needs Wi-Fi; not on mobile data or the
phone's own hotspot):

1. Developer options › **Wireless debugging** › Pair device with pairing code. Pair **right after**
   reading the code; a stale code gives `protocol fault`. Split screen with Termux helps.
   ```bash
   adb pair 127.0.0.1:<pairing port> <code>
   ```
2. Connect to the port on the main Wireless debugging screen (a different one), and install:
   ```bash
   adb connect 127.0.0.1:<port>
   adb -s 127.0.0.1:<port> install -r ~/qb-build/_app/outputs/apk/release/app-release.apk
   ```

Pairing is kept. After a reboot only `adb connect` is needed, and the port changes each time. Pass
`-s` every time: `adb devices` can list a phantom `emulator-5554` next to the real device.

Useful from there:

- `adb logcat` for what the app's own log misses.
- `adb shell am start -n com.spizganed.quickbuds/.ui.MainActivity` to launch. Avoid `monkey`.
- `adb shell uiautomator dump` to find a view's coordinates, then `adb shell input tap X Y`.
- `adb exec-out screencap -p > shot.png`.
- `adb bugreport` for the Bluetooth HCI log ([PACKET-CAPTURE.md](./PACKET-CAPTURE.md)).
- A test that rotates the screen: turn auto-rotate off again after it
  (`adb shell settings put system accelerometer_rotation 0`).

## 6. SSH from a PC

The PC needs nothing but an SSH client. On the phone:

```bash
passwd          # or put your PC's public key in ~/.ssh/authorized_keys
sshd
termux-wake-lock
```

From the PC: `ssh -p 8022 <anything>@<phone ip>` (Termux ignores the user name). A silent timeout while
ping works usually means the `-p 8022` was left out.

`termux-wake-lock` keeps Android from suspending Termux while the screen is off. With the Termux:Widget
add-on, a script in `~/.shortcuts/` puts "start SSH" on the home screen:

```bash
#!/data/data/com.termux/files/usr/bin/bash
pgrep -x sshd >/dev/null || sshd
termux-wake-lock
ifconfig 2>/dev/null | grep -oE 'inet [0-9.]+' | awk '{print $2}' | grep -v '^127\.'
```

Start a build over SSH and it keeps going on the phone. The PC only shows the output.

## 7. Captures on the phone

`tshark` comes with Wireshark from Termux's x11 repository:
`pkg install x11-repo && pkg install wireshark-qt`. The Bluetooth HCI log is pulled with
`adb bugreport` as in [PACKET-CAPTURE.md](./PACKET-CAPTURE.md) and read in Termux, no PC needed.

## Gotchas

- Never commit the aapt2 override or the init script to the repo: a PC build breaks on it.
- Keep the release key and its passwords out of the repo, and back them up. An app only updates over
  an APK signed with the same key.
- Build under `~` (Termux's own storage). Shared storage (`/sdcard`) is slow for Gradle.
- A debug build cannot install over a release build (different key). Pick one, or uninstall between.
