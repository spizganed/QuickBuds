# Install QuickBuds on Linux

Pair your earbuds in the system's Bluetooth settings first. QuickBuds connects to paired earbuds.

## What the system needs

The AppImage holds the app and its libraries. It works on any x64 distribution: Arch, Fedora, Debian, Ubuntu and others.
The system must give 3 things:

- **BlueZ**, the Bluetooth service of Linux. No app can bring it along. Install it and start it:

  | Distribution | Command |
  |---|---|
  | Arch, Omarchy, Manjaro, CachyOS | `sudo pacman -S bluez bluez-utils && sudo systemctl enable --now bluetooth` |
  | Debian, Ubuntu, Mint | `sudo apt install bluez && sudo systemctl enable --now bluetooth` |
  | Fedora | `sudo dnf install bluez && sudo systemctl enable --now bluetooth` |

- **glibc 2.39 or later.** The AppImage is built on Ubuntu 24.04. Debian 13, Fedora 40, Ubuntu 24.04 and a current Arch have it. Debian 12 does not.
- **A tray host** for the tray icon. KDE, Cinnamon, XFCE and Waybar (Hyprland, Omarchy) have one. GNOME needs the AppIndicator extension.

Pair the earbuds once in the Bluetooth settings, or in a terminal:

```sh
bluetoothctl
scan on
pair <address>
trust <address>
connect <address>
```

## Script (recommended)

```sh
curl -fsSL https://raw.githubusercontent.com/spizganed/QuickBuds/main/scripts/install-linux.sh | sh
```

It needs only `curl` and BlueZ, and no administrator rights. It puts the AppImage in `~/.local/share/quickbuds` and adds a `quickbuds` command and a menu entry. The app updates itself.

## Remove it

```sh
curl -fsSL https://raw.githubusercontent.com/spizganed/QuickBuds/main/scripts/install-linux.sh | sh -s uninstall
```

## Without the script

Download the file from the [latest release](https://github.com/spizganed/QuickBuds/releases/latest):

- `QuickBuds-linux-x64.AppImage`: make it executable and run it.
