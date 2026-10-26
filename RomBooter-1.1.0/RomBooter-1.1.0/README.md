# RomBooter

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-support-FFDD00?logo=buymeacoffee&logoColor=black)](https://buymeacoffee.com/3rr0r303YT)

Run full Linux desktops on your Android phone. **No root, no emulation overhead, no telemetry.**

RomBooter drives Termux + PRoot (`proot-distro`) to run Linux distributions natively on ARM64 and shows the desktop in an embedded VNC viewer. One tap on **Boot** starts the container and switches to the live desktop automatically.

## What's new in 1.1.0

- **Fixed:** `java.io.EOFException` when pressing **Boot**, caused by an empty VNC password file on Debian 13, Ubuntu 24.04+, Kali and Arch.
- **Fixed:** "Server refused:" with an empty message, caused by TigerVNC blacklisting 127.0.0.1 after failed logins and port checks.
- **Fixed:** start script parameters (`--pass`, `--res`, `--de`) are validated, with clear errors and exit codes.
- **Fixed:** sessions left over from another distro or password are replaced, `--stop` is reliable, and a busy X display `:1` is handled.
- **Fixed:** copying files into the container is verified with a checksum on all 7 distros. Gentoo `MAKEOPTS` and the Arch keyring are corrected.
- **New:** experimental Wayland sessions (Sway, Phosh mobile shell) with a session registry inside the container.
- **New:** the app shows the start script's real error, retries the connection, and restarts a blocked session by itself.
- **New:** tests for all scripts, plus Docker end-to-end tests.

Details: [Fixes in 1.1.0](#fixes-in-110-javaioeofexception-on-boot) and [CHANGELOG.md](CHANGELOG.md). **Updating from 1.0.x:** run `bash rombooter-setup.sh --distro <distro>` once more in Termux.

## Features

- **No-root PRoot execution**: native ARM64 speed through Termux + PRoot, no QEMU x86 emulation, no root.
- **Multi-distro**: Arch, Gentoo, Debian, Ubuntu, Alpine, Kali, Fedora.
- **Sessions**: XFCE or i3 on X11 (Xvnc), plus experimental Wayland sessions (Sway, Phosh) through a headless wlroots compositor and `wayvnc`. See [Sessions and display backends](#sessions-and-display-backends).
- **Embedded VNC client**: minimal RFB 3.3/3.8 viewer written in Kotlin (VncAuth or None, Raw + CopyRect + DesktopSize), touch to mouse, on-screen Esc/Tab/text input.
- **Zero-click boot**: the app launches the start script in Termux, gets its exit status back from Termux, and opens the desktop once the VNC server answers on `127.0.0.1:5901`. If something fails, the app shows the script's error message instead of a bare exception.
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

Options: `--distro arch|gentoo|debian|ubuntu|alpine|kali|fedora`, `--de LIST` (comma-separated, e.g. `xfce,i3,sway`; the first one is the default), `--res WxH`, `--user NAME`, `--yes`, `--no-update`, `--list`, `--version`. `--opt=value` works too.

The VNC server listens on `127.0.0.1:5901` only. The app generates a per-install 8-character password and passes it to the start script, which hands it to the container through stdin, so it never appears in a process list.

Notes:
- The setup script sets `allow-external-apps = true` in `~/.termux/termux.properties` so the app can call Termux. Only apps you grant the `com.termux.permission.RUN_COMMAND` permission can use it.
- Gentoo uses binary packages where available; anything else compiles on the device and can take hours.
- Kali and Gentoo rootfs hashes are pinned from your own download (trust on first use).
- On Android 12+ the phantom-process killer can stop PRoot sessions:
  `adb shell device_config put activity_manager max_phantom_processes 2147483647`

## How the setup works (universal distro support)

`rombooter-setup.sh` never assumes where `proot-distro` keeps a container on disk. That layout changed between `proot-distro` versions and is the reason for the 1.0.0 failure below. Instead it talks to every distro the same way, through `proot-distro login`:

1. **Detect**: `proot-distro login <distro> -- true` decides whether the container is already installed. If not, it runs `proot-distro install`.
2. **Copy in**: the provisioning script (`/root/rb-provision.sh`) and the session launcher (`/usr/local/bin/rb-session`) are streamed into the container through stdin, so they land in the container's own root regardless of host paths.
3. **Verify**: before running anything, the script checks from inside the container that both files exist and are non-empty. If not, it stops with a clear error instead of a cryptic `/bin/sh` message.
4. **Provision**: a per-family package script runs inside the container: `apt` (Debian, Ubuntu, Kali), `pacman` (Arch), `apk` (Alpine), `dnf` (Fedora), `emerge` (Gentoo). Kali and Gentoo are not in `proot-distro` itself, so a small plugin is registered for them first.

Re-running the script is safe: an installed container is reused, users and packages are only added when missing.

Copying files in (step 2) writes to a temporary name, renames it atomically and compares the `cksum` from inside the container with the host copy. Failed copies are retried 3 times. This is tested against the container images of all 7 distros (`tests/e2e-copy.sh`).

## Sessions and display backends

The app works like a small display manager, similar to postmarketOS's `tinydm`. Each session is registered inside the container as `/etc/rombooter/sessions/<name>.conf` (`RB_BACKEND`, `RB_EXEC`, `RB_LABEL`). `rb-session` picks the backend from that file:

| Session | Backend | How it is shown | Status |
|---------|---------|-----------------|--------|
| `xfce`  | `x11`     | TigerVNC `Xvnc :1`, VNC password | stable |
| `i3`    | `x11`     | TigerVNC `Xvnc :1`, VNC password | stable |
| `sway`  | `wayland` | `sway` on the wlroots headless backend + `wayvnc` | experimental |
| `phosh` | `wayland` | `phoc` (headless) + `phosh` mobile shell + `wayvnc`, portrait framebuffer | experimental, not on Gentoo |

Install several sessions with `--de xfce,sway` and pick one in the app or with `~/start_<distro>.sh --de sway`. `rb-session --list` shows what is installed. Limits of the Wayland sessions: `wayvnc` cannot use classic VNC passwords, so these sessions have no authentication and rely on the server listening only on loopback. Any app on the phone could connect to them while they run. They use software rendering (pixman).

Adding a backend means adding a `run_<backend>` function to `rb-session`, so for example a future native Wayland viewer would not need changes to the provisioning or the app's boot flow.

### Fixes in 1.1.0: `java.io.EOFException` on Boot

The app could fail with a bare `java.io.EOFException`: the VNC server accepted the connection and then hung up during the handshake. Causes and fixes:

- **Root cause on Debian 13 / Ubuntu 24.04+ / current Kali:** TigerVNC 1.13 moved `vncpasswd` from `tigervnc-common` into the new `tigervnc-tools` package. `vncpasswd -f` failed silently, the password file stayed empty, and Xvnc dropped every client that tried VNC authentication. Provisioning now installs `tigervnc-tools` when the distro has it, checks that `Xvnc`, `vncpasswd` and every session binary exist, and `rb-session` refuses to start with an empty password file instead of starting a broken server.
- **"Server refused:" with an empty message:** this follows from the empty password file above. TigerVNC counts every connection that closes before login as a failed attempt, including plain port checks. After 5 of them it puts 127.0.0.1 on its blacklist and answers every client with an empty list of security types and no reason. The app showed that as "Server refused:". Xvnc now blacklists only after 20 attempts and for 2 seconds at first (the block still doubles while attempts continue). The start script waits for a ready marker from `rb-session` instead of polling the port, so a boot costs at most 1 or 2 probes, and a successful login resets the count. The app explains a refusal and, after a refusal or a failed login, restarts the session once (`--restart`) and connects again.
- **Busy display `:1`:** another X server in the same network namespace, such as Termux's own `vncserver` or a second distro, made Xvnc fail with "server already running". `rb-session` now uses the first free display from `:1` to `:9`. It removes stale lock files only when the server that owned them is gone.
- **Start script arguments:** `--pass`, `--res` and `--de` are validated with clear messages and exit code 2. `--pass` followed by another option is no longer swallowed as the password, `--opt=value` works, and the password may be 6 to 64 printable characters (VNC only uses the first 8).
- **Stale or foreign sessions:** a session that was started for another distro, session, resolution or password is restarted instead of being reported as "already running". Before, a different password led to an authentication failure, and a dead server led to the EOF.
- **READY means usable:** the start script reports `READY` only after `rb-session` has signalled that Xvnc is up and the server has answered one check with an RFB banner. Before, an open port was enough.
- **Stopping:** each session runs in its own process group (`setsid`), so `--stop` stops proot and everything inside it. The old `pkill -f rb-session` could match unrelated processes.
- **App:** gets the start script's exit status and stderr from Termux (`RUN_COMMAND_PENDING_INTENT`) and shows the real reason. It also retries the VNC handshake up to 3 times while a session is still settling, maps EOF and timeouts to readable messages, reads the server's failure reason, supports RFB 3.3 and DesktopSize, and has a 15 s handshake timeout.

Start script exit codes: `0` ready or stopped, `1` session exited during startup (the log tail is printed), `2` bad arguments, `3` timeout, `4` distro not set up, `5` port 5901 used by another program.

### Fixes in 1.0.1

- **Fixed:** `/bin/sh: 0: cannot open /root/rb-provision.sh: No such file` during provisioning. 1.0.0 wrote the file to a hard-coded host path (`.../installed-rootfs/<distro>/root/`), which does not match the container's real root on newer `proot-distro` layouts. The file is now copied in through `proot-distro login`.
- **Fixed:** the "already installed" check no longer looks for a host directory, so a leftover empty folder can no longer make an uninstalled distro look installed.
- **Added:** explicit existence checks inside the container before and after provisioning.

## Running the setup properly

Use Termux from F-Droid or GitHub (not the Play Store build), then in Termux:

```bash
pkg install -y curl
curl -fsSLO https://raw.githubusercontent.com/Real3rr0r303YT/RomBooter/main/rombooter-setup.sh
bash rombooter-setup.sh --distro debian --de xfce --yes
```

Then start and stop the desktop:

```bash
~/start_debian.sh --res 1272x576 --pass <6-64 chars, no spaces> --de xfce
~/start_debian.sh --status
~/start_debian.sh --stop
```

The app calls exactly this script, so `~/start_<distro>.sh` must exist (it is created by the setup) before pressing **Boot**.

Troubleshooting:
- Setup failed halfway: just run the same command again.
- Updating from 1.0.x: run the setup again with the same `--distro`. This regenerates `start_<distro>.sh` and `rb-session` and installs `tigervnc-tools` where it is needed.
- Desktop does not come up: `cat ~/.rombooter/<distro>.log`.
- Test the container by hand: `proot-distro login debian`.

## Tests

```bash
bash tests/static.sh          # shellcheck + dash/busybox syntax of every generated script, all 7 distros
bash tests/start-script.sh    # start script: argument validation, password via stdin, restart/stop, exit codes
bash tests/e2e-copy.sh        # docker: real copy-in into all 7 distro images, guest-side checks
bash tests/e2e-docker.sh ubuntu:xfce,i3,sway   # docker: full provision + boot + VNC handshake (needs package mirrors),
                                               # plus blacklist/"Server refused" recovery, 1.0.x server replacement, busy :1
```

The Docker tests replace `proot-distro` with a small wrapper around `docker` (`tests/e2e/proot-distro`). The VNC check (`tests/e2e/RfbProbe.java`) uses the same handshake as the app.

## Repository layout

```
rombooter-setup.sh                      setup + start-script generator (run in Termux)
tests/                                  shell, docker and VNC handshake tests
app/src/main/AndroidManifest.xml
app/src/main/java/org/rombooter/
  MainActivity.kt                       entry point
  RomBooterApp.kt                       Compose UI: boot screen, VNC canvas, support dialog
  BootViewModel.kt                      launch -> wait for :5901 -> connect state machine
  TermuxLauncher.kt                     Termux RUN_COMMAND intents
  RfbClient.kt                          minimal RFB/VNC client
```

## Status

Early development. CI builds a debug APK on every push. The setup and start scripts are tested in CI and with Docker, but the app has not been tested on a real device yet. Issues and pull requests are welcome.

Dependencies for the app module: Compose Material 3, `androidx.lifecycle:lifecycle-viewmodel-compose`, `kotlinx-coroutines-android`.

## Support

If RomBooter is useful to you, you can support development:

[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-support-FFDD00?logo=buymeacoffee&logoColor=black)](https://buymeacoffee.com/3rr0r303YT)

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
