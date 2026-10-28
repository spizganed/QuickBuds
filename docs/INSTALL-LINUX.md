# Install QuickBuds on Linux

Pair your earbuds in the system's Bluetooth settings first. QuickBuds connects to paired earbuds.

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

Download one file from the [latest release](https://github.com/spizganed/QuickBuds/releases/latest):

- `QuickBuds-linux-x64.AppImage`: make it executable and run it.
- `QuickBuds<version>-linux-x64.tar.gz`: needs BlueZ, GTK 3 and libayatana-appindicator.
