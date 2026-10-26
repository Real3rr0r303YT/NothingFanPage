# RomBooter

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-support-FFDD00?logo=buymeacoffee&logoColor=black)](https://buymeacoffee.com/3rr0r303YT)

Run full Linux desktops on your Android phone. **No root, no emulation overhead, no telemetry.**

RomBooter drives Termux + PRoot (`proot-distro`) to run Linux distributions natively on ARM64 and shows the desktop in an embedded VNC viewer. One tap on **Boot** starts the container and switches to the live desktop automatically.

## Features

- **No-root PRoot execution**: native ARM64 speed through Termux + PRoot, no QEMU x86 emulation, no root.
- **Multi-distro**: Arch, Gentoo, Debian, Ubuntu, Alpine, Kali, Fedora; XFCE or i3.
- **Embedded VNC client**: minimal RFB 3.8 viewer written in Kotlin (VncAuth, Raw + CopyRect), touch to mouse, on-screen Esc/Tab/text input.
- **Zero-click boot**: the app launches the start script in Termux, waits for `127.0.0.1:5901`, then opens the desktop.
- **Zero telemetry**: no analytics, no phone-home. The app only talks to loopback. Network is used only during setup for distro package mirrors and rootfs downloads.
- **Audio**: PulseAudio on the Termux side, loopback-only TCP.

## Quick start (Termux)

Install Termux from F-Droid or GitHub, then:

```bash
bash rombooter-setup.sh --distro debian --de xfce --yes
bash rombooter-setup.sh                 # interactive menu
~/start_debian.sh --res 1280x608        # start
~/start_debian.sh --stop                # stop
```

Options: `--distro arch|gentoo|debian|ubuntu|alpine|kali|fedora`, `--de xfce|i3`, `--res WxH`, `--user NAME`, `--yes`, `--no-update`, `--list`.

The VNC server listens on `127.0.0.1:5901` only. The app generates a per-install 8-character password and passes it to the start script.

Notes:
- The setup script sets `allow-external-apps = true` in `~/.termux/termux.properties` so the app can call Termux. Only apps you grant the `com.termux.permission.RUN_COMMAND` permission can use it.
- Gentoo uses binary packages where available; anything else compiles on the device and can take hours.
- Kali and Gentoo rootfs hashes are pinned from your own download (trust on first use).
- On Android 12+ the phantom-process killer can stop PRoot sessions:
  `adb shell device_config put activity_manager max_phantom_processes 2147483647`

## Repository layout

```
rombooter-setup.sh                      setup + start-script generator (run in Termux)
app/src/main/AndroidManifest.xml
app/src/main/java/org/rombooter/
  MainActivity.kt                       entry point
  RomBooterApp.kt                       Compose UI: boot screen, VNC canvas, support dialog
  BootViewModel.kt                      launch -> wait for :5901 -> connect state machine
  TermuxLauncher.kt                     Termux RUN_COMMAND intents
  RfbClient.kt                          minimal RFB/VNC client
```

## Status

Early development. The Gradle project files are not included yet, and the code has not been tested on a device. Issues and pull requests are welcome.

Dependencies for the app module: Compose Material 3, `androidx.lifecycle:lifecycle-viewmodel-compose`, `kotlinx-coroutines-android`.

## Support

If RomBooter is useful to you, you can support development:

[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-support-FFDD00?logo=buymeacoffee&logoColor=black)](https://buymeacoffee.com/3rr0r303YT)

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
