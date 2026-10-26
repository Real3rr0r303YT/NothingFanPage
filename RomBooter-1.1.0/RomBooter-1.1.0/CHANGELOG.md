# Changelog

## 1.1.0

### Fixed
- `java.io.EOFException` when booting. On TigerVNC >= 1.13 (Debian 13, Ubuntu 24.04+, Kali) `vncpasswd` lives in `tigervnc-tools`, which was not installed. The VNC password file stayed empty and Xvnc dropped every client mid-handshake. Provisioning now installs it and checks all required binaries. `rb-session` refuses an empty password file.
- "Server refused:" with an empty reason. Failed logins against the empty password file got 127.0.0.1 blacklisted by TigerVNC. Plain port checks count as failed attempts too. Xvnc now uses `-BlacklistThreshold 20 -BlacklistTimeout 2`, and the start script waits for an `RB_READY` marker from `rb-session` instead of polling (at most 1 probe per boot). The app polls only as a fallback for Termux builds without a result report, every 10 s after 20 s. The app names the cause and restarts the session once with `--restart`.
- Xvnc no longer fails when display `:1` is taken by another X server: `rb-session` picks the first free display from `:1` to `:9`.
- Start script: strict validation of `--pass`, `--res` and `--de` with clear errors and documented exit codes (`2` = bad arguments). A flag is never taken as an option value. `--opt=value` is supported.
- Start script: a running session with another distro, session, resolution or password is restarted instead of being reused.
- Start script: `READY` only after the server sends an RFB banner and survives its first client.
- Start script: sessions run in their own process group, so `--stop` is reliable and no longer uses a broad `pkill -f rb-session`.
- Gentoo: `MAKEOPTS="-j$(nproc)"` was written to make.conf literally, and Portage does not expand it. It is now expanded once, and repeated runs no longer append the block again.
- Arch: `pacman-key --populate` now loads every installed keyring.
- Copy-in: atomic write plus checksum verification inside the container, with retries.

### Added
- Session registry `/etc/rombooter/sessions/*.conf` and backend dispatch in `rb-session` (`x11`, `wayland`).
- Experimental Wayland sessions: `sway` and `phosh` (mobile shell, portrait) through a headless wlroots compositor and `wayvnc`.
- `--de` takes a list (`xfce,i3,sway`). New start script options: `--status` and `--restart`.
- App: session picker, start script exit status and stderr from Termux, handshake retries with readable errors, RFB 3.3, DesktopSize, handshake timeout.
- Tests: `tests/static.sh`, `tests/start-script.sh`, `tests/e2e-copy.sh`, `tests/e2e-docker.sh`. CI runs the first three.

## 1.0.1
- Files are copied into the container through `proot-distro login` instead of a hard-coded rootfs path.
