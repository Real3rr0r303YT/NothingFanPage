#!/data/data/com.termux/files/usr/bin/bash
# rombooter-setup.sh - RomBooter PRoot desktop provisioning (Termux, aarch64, no root)
# License: GPL-3.0-or-later | Offline at runtime | Zero telemetry
# Network use: Termux/distro package mirrors + rootfs download during SETUP only.
set -Eeuo pipefail

readonly VERSION="1.0.1"
: "${PREFIX:=/data/data/com.termux/files/usr}"
: "${HOME:=/data/data/com.termux/files/home}"
readonly PLUGIN_DIR="$PREFIX/etc/proot-distro"
readonly DLCACHE="$PREFIX/var/lib/proot-distro/dlcache"

# ---------- ANSI output ----------
if [[ -t 1 && -z "${NO_COLOR:-}" ]]; then
  C_R=$'\e[31m'; C_G=$'\e[32m'; C_Y=$'\e[33m'; C_B=$'\e[34m'; C_C=$'\e[36m'; C_BD=$'\e[1m'; C_X=$'\e[0m'
else
  C_R=""; C_G=""; C_Y=""; C_B=""; C_C=""; C_BD=""; C_X=""
fi
info() { printf '%s[*]%s %s\n' "$C_B" "$C_X" "$*"; }
ok()   { printf '%s[+]%s %s\n' "$C_G" "$C_X" "$*"; }
warn() { printf '%s[!]%s %s\n' "$C_Y" "$C_X" "$*" >&2; }
err()  { printf '%s[-]%s %s\n' "$C_R" "$C_X" "$*" >&2; }
die()  { err "$*"; exit 1; }
trap 'rc=$?; err "Failed (exit $rc) at line $LINENO: $BASH_COMMAND"; exit $rc' ERR
trap 'err "Interrupted"; exit 130' INT TERM

# ---------- defaults ----------
DISTRO=""; DE="xfce"; RES="1280x608"; USER_NAME="rom"; ASSUME_YES=0; SKIP_UPDATE=0
KEY=""; ALIAS=""; FAMILY=""; ROOTFS_FILE=""

usage() {
  cat <<EOF
${C_BD}rombooter-setup.sh ${VERSION}${C_X}
Usage: $0 [options]
  -d, --distro NAME   arch | gentoo | debian | ubuntu | alpine | kali | fedora
  -e, --de NAME       xfce (default) | i3
  -r, --res WxH       default VNC resolution (default: $RES)
  -u, --user NAME     unprivileged guest user (default: $USER_NAME)
  -y, --yes           no confirmation prompt
      --no-update     skip 'pkg update'
      --list          list distro keys
  -h, --help          this help
Without --distro an interactive menu is shown.
EOF
}
list_distros() { printf '%s\n' arch gentoo debian ubuntu alpine kali fedora; }

parse_args() {
  while (($#)); do
    case $1 in
      -d|--distro)  DISTRO=${2:?--distro needs a value}; shift 2 ;;
      -e|--de)      DE=${2:?--de needs a value}; shift 2 ;;
      -r|--res)     RES=${2:?--res needs a value}; shift 2 ;;
      -u|--user)    USER_NAME=${2:?--user needs a value}; shift 2 ;;
      -y|--yes)     ASSUME_YES=1; shift ;;
      --no-update)  SKIP_UPDATE=1; shift ;;
      --list)       list_distros; exit 0 ;;
      -h|--help)    usage; exit 0 ;;
      *)            usage >&2; die "Unknown option: $1" ;;
    esac
  done
  [[ $DE == xfce || $DE == i3 ]]                 || die "--de must be xfce or i3"
  [[ $RES =~ ^[0-9]{3,4}x[0-9]{3,4}$ ]]          || die "--res must look like 1280x608"
  [[ $USER_NAME =~ ^[a-z_][a-z0-9_-]{0,30}$ ]]   || die "invalid --user"
}

preflight() {
  [[ $PREFIX == *com.termux* ]] || die "Run this inside Termux."
  [[ $(id -u) -ne 0 ]]          || die "Do not run as root."
  case $(uname -m) in aarch64|arm64) ;; *) die "ARM64 only (found $(uname -m))." ;; esac
}

menu() {
  [[ -t 0 ]] || die "No --distro given and no TTY for the menu."
  PS3=$'\n'"${C_C}Distro [1-7]: ${C_X}"
  local d
  select d in arch gentoo debian ubuntu alpine kali fedora; do
    [[ -n ${d:-} ]] && { DISTRO=$d; break; }
  done
}

resolve_distro() {
  case ${DISTRO,,} in
    arch|archlinux) KEY=arch;   ALIAS=archlinux; FAMILY=arch ;;
    gentoo)         KEY=gentoo; ALIAS=gentoo;    FAMILY=gentoo ;;
    debian)         KEY=debian; ALIAS=debian;    FAMILY=deb ;;
    ubuntu)         KEY=ubuntu; ALIAS=ubuntu;    FAMILY=deb ;;
    alpine)         KEY=alpine; ALIAS=alpine;    FAMILY=alpine ;;
    kali)           KEY=kali;   ALIAS=kali;      FAMILY=deb ;;
    fedora)         KEY=fedora; ALIAS=fedora;    FAMILY=fedora ;;
    *) die "Unknown distro '$DISTRO' (see --list)" ;;
  esac
}

confirm() {
  if [[ $KEY == gentoo ]]; then
    warn "Gentoo: packages come from the binhost where possible; anything else compiles on-device (hours)."
  fi
  if ((ASSUME_YES)) || [[ ! -t 0 ]]; then return 0; fi
  local a
  read -r -p "Install ${C_BD}${KEY}${C_X} + ${DE} as user '${USER_NAME}'? [Y/n] " a
  [[ ${a:-y} =~ ^[Yy]$ ]] || die "Aborted."
}

# ---------- host (Termux) side ----------
install_host_deps() {
  info "Installing Termux packages"
  export DEBIAN_FRONTEND=noninteractive
  ((SKIP_UPDATE)) || pkg update -y
  pkg install -y proot-distro pulseaudio procps curl coreutils tar xz-utils
  ok "Host packages ready"
}

enable_termux_external() {
  local f="$HOME/.termux/termux.properties"
  mkdir -p "$HOME/.termux"; touch "$f"
  if grep -q '^[#[:space:]]*allow-external-apps' "$f"; then
    sed -i 's/^[#[:space:]]*allow-external-apps.*/allow-external-apps = true/' "$f"
  else
    echo 'allow-external-apps = true' >>"$f"
  fi
  termux-reload-settings >/dev/null 2>&1 || true
  warn "allow-external-apps=true: apps holding the RUN_COMMAND permission (you grant it per app) can run commands in Termux."
}

# Download rootfs ourselves (resumable), pin its SHA-256 in a proot-distro plugin.
# The hash is trust-on-first-use over HTTPS; verify manually if you need stronger guarantees.
register_plugin() { # name pretty url strip
  local name=$1 pretty=$2 url=$3 strip=$4 file sum
  file="$DLCACHE/${url##*/}"
  mkdir -p "$DLCACHE" "$PLUGIN_DIR"
  if [[ ! -s $file ]]; then
    info "Downloading ${url##*/}"
    curl -fL --retry 5 --retry-delay 2 -C - -o "$file.part" "$url"
    mv "$file.part" "$file"
  fi
  sum=$(sha256sum "$file" | cut -d' ' -f1)
  cat >"$PLUGIN_DIR/$name.sh" <<EOF
DISTRO_NAME="$pretty"
DISTRO_COMMENT="Registered by rombooter-setup $VERSION"
TARBALL_STRIP_OPT=$strip
TARBALL_URL['aarch64']="$url"
TARBALL_SHA256['aarch64']="$sum"
EOF
  ok "Plugin '$name' registered"
}

prepare_distro() {
  case $KEY in
    kali)
      register_plugin kali "Kali Linux (minimal arm64)" \
        "https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz" 1 ;;
    gentoo)
      local base="https://distfiles.gentoo.org/releases/arm64/autobuilds" path
      info "Resolving latest Gentoo stage3"
      path=$(curl -fsSL "$base/latest-stage3-arm64-openrc.txt" | awk '/\.tar\.xz / && !p {print $1; p=1}')
      [[ -n $path ]] || die "Could not resolve Gentoo stage3 path"
      register_plugin gentoo "Gentoo (stage3 arm64 openrc)" "$base/$path" 0 ;;
  esac
}

# proot-distro changed its on-disk layout between versions, so never assume a rootfs path:
# ask proot-distro itself whether the container is usable, and talk to it only via `login`.
guest_sh() { proot-distro login "$ALIAS" -- /bin/sh -c "$1"; }

install_distro() {
  if guest_sh ':' >/dev/null 2>&1; then
    ok "$ALIAS already installed - reusing"
  else
    info "Installing $ALIAS via proot-distro"
    proot-distro install "$ALIAS"
    guest_sh ':' >/dev/null 2>&1 || die "$ALIAS installed but 'proot-distro login $ALIAS' fails; run it manually to see why."
  fi
}

# ---------- guest side: provisioning scripts (POSIX sh, run as root in the distro) ----------
# Guest only needs PulseAudio CLIENT libs; the daemon runs in Termux and is reached via TCP.
write_provision() {
  case $FAMILY in
    deb) cat <<'RB_DEB'
set -e
U="$1"; DE="$2"
export DEBIAN_FRONTEND=noninteractive
echo 'APT::Sandbox::User "root";' >/etc/apt/apt.conf.d/99rb-nosandbox
apt-get update
apt-get install -y --no-install-recommends tigervnc-standalone-server tigervnc-common dbus dbus-x11 \
  pulseaudio-utils x11-xserver-utils xauth xfonts-base fonts-dejavu-core ca-certificates procps
case "$DE" in
  i3) apt-get install -y i3 i3status dmenu xterm ;;
  *)  apt-get install -y xfce4 xfce4-terminal ;;
esac
id "$U" >/dev/null 2>&1 || useradd -m -s /bin/bash "$U"
usermod -aG audio,video "$U" 2>/dev/null || true
RB_DEB
    ;;
    arch) cat <<'RB_ARCH'
set -e
U="$1"; DE="$2"
sed -i 's/^CheckSpace/#CheckSpace/' /etc/pacman.conf
grep -q '^DisableSandbox' /etc/pacman.conf || sed -i '/^\[options\]/a DisableSandbox' /etc/pacman.conf
pacman-key --init >/dev/null 2>&1 || true
pacman-key --populate archlinuxarm >/dev/null 2>&1 || true
pacman -Syu --noconfirm --needed tigervnc dbus xorg-xauth xorg-xset xorg-xsetroot ttf-dejavu libpulse procps-ng
case "$DE" in
  i3) pacman -S --noconfirm --needed i3-wm i3status dmenu xterm ;;
  *)  pacman -S --noconfirm --needed xfce4 xfce4-terminal ;;
esac
id "$U" >/dev/null 2>&1 || useradd -m -s /bin/bash "$U"
RB_ARCH
    ;;
    alpine) cat <<'RB_ALPINE'
set -e
U="$1"; DE="$2"
apk update
apk add --no-cache bash shadow tigervnc dbus dbus-x11 xauth xset xsetroot font-dejavu pulseaudio-utils
case "$DE" in
  i3) apk add --no-cache i3wm i3status dmenu xterm ;;
  *)  apk add --no-cache xfce4 xfce4-terminal ;;
esac
id "$U" >/dev/null 2>&1 || adduser -D -s /bin/bash "$U"
addgroup "$U" audio 2>/dev/null || true
RB_ALPINE
    ;;
    fedora) cat <<'RB_FEDORA'
set -e
U="$1"; DE="$2"
dnf -y --setopt=install_weak_deps=False install tigervnc-server dbus-x11 xorg-x11-xauth \
  pulseaudio-utils dejavu-sans-fonts shadow-utils procps-ng
case "$DE" in
  i3) dnf -y install i3 i3status dmenu xterm ;;
  *)  dnf -y install @xfce-desktop-environment ;;
esac
id "$U" >/dev/null 2>&1 || useradd -m -s /bin/bash "$U"
RB_FEDORA
    ;;
    gentoo) cat <<'RB_GENTOO'
set -e
U="$1"; DE="$2"
cat >>/etc/portage/make.conf <<'MK'
FEATURES="${FEATURES} -sandbox -usersandbox -network-sandbox -ipc-sandbox -pid-sandbox"
MAKEOPTS="-j$(nproc)"
MK
mkdir -p /etc/portage/package.use
echo 'net-misc/tigervnc server' >/etc/portage/package.use/rombooter
getuto >/dev/null 2>&1 || true
emerge-webrsync -q
EM="emerge -q --getbinpkg --binpkg-respect-use=y --noreplace"
$EM net-misc/tigervnc sys-apps/dbus x11-apps/xset x11-apps/xsetroot media-libs/libpulse media-fonts/dejavu
case "$DE" in
  i3) $EM x11-wm/i3 x11-misc/i3status x11-misc/dmenu x11-terms/xterm ;;
  *)  $EM xfce-base/xfce4-meta x11-terms/xfce4-terminal ;;
esac
id "$U" >/dev/null 2>&1 || useradd -m -s /bin/bash "$U"
RB_GENTOO
    ;;
  esac
}

# ---------- guest side: session launcher (installed to /usr/local/bin/rb-session) ----------
write_session() {
  cat <<'RB_SESSION'
#!/bin/sh
# rb-session RES PASS DE   (runs inside the distro as the unprivileged user)
RES="${1:-1280x608}"; PASS="${2:-}"; DE="${3:-xfce}"
export DISPLAY=:1 PULSE_SERVER=127.0.0.1 NO_AT_BRIDGE=1 GDK_BACKEND=x11 \
       LIBGL_ALWAYS_SOFTWARE=1 XDG_SESSION_TYPE=x11
export XDG_RUNTIME_DIR="/tmp/rb-runtime-$(id -u)"
mkdir -p "$XDG_RUNTIME_DIR" "$HOME/.vnc"; chmod 700 "$XDG_RUNTIME_DIR"
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null || true

if [ -n "$PASS" ]; then
  printf '%s\n' "$PASS" | vncpasswd -f >"$HOME/.vnc/passwd"
  chmod 600 "$HOME/.vnc/passwd"
  SEC="-SecurityTypes VncAuth -rfbauth $HOME/.vnc/passwd"
else
  SEC="-SecurityTypes None"
fi

XC="$HOME/.config/xfce4/xfconf/xfce-perchannel-xml"
if [ "$DE" = xfce ] && [ ! -f "$XC/xfwm4.xml" ]; then
  mkdir -p "$XC"
  cat >"$XC/xfwm4.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="use_compositing" type="bool" value="false"/>
  </property>
</channel>
XML
fi
if [ "$DE" = i3 ] && [ ! -f "$HOME/.config/i3/config" ]; then
  mkdir -p "$HOME/.config/i3"
  cp /etc/i3/config "$HOME/.config/i3/config" 2>/dev/null \
    || cp /etc/xdg/i3/config "$HOME/.config/i3/config" 2>/dev/null || true
fi

# shellcheck disable=SC2086
Xvnc :1 -geometry "$RES" -depth 24 -rfbport 5901 -localhost -AlwaysShared -ac $SEC &
XPID=$!
trap 'kill $XPID 2>/dev/null' EXIT INT TERM

i=0
while [ ! -S /tmp/.X11-unix/X1 ] && [ "$i" -lt 20 ]; do sleep 1; i=$((i + 1)); done
[ -S /tmp/.X11-unix/X1 ] || { echo "Xvnc did not start" >&2; exit 1; }

xsetroot -solid '#1b1b2f' 2>/dev/null || true
case "$DE" in i3) CMD=i3 ;; *) CMD=startxfce4 ;; esac
if command -v dbus-launch >/dev/null 2>&1; then
  dbus-launch --exit-with-session $CMD
else
  $CMD
fi
RB_SESSION
}

# Copy stdin into a file INSIDE the container (works for every distro and every proot-distro layout).
guest_put() { # dest mode
  proot-distro login "$ALIAS" -- /bin/sh -c 'mkdir -p "$(dirname -- "$1")" && cat >"$1" && chmod "$2" "$1"' sh "$1" "$2"
}
guest_has() { # path -> true if non-empty file exists inside the container
  proot-distro login "$ALIAS" -- /bin/sh -c 'test -s "$1"' sh "$1" >/dev/null 2>&1
}

provision() {
  local prov=/root/rb-provision.sh sess=/usr/local/bin/rb-session
  info "Copying provisioning files into the $ALIAS container"
  write_provision | guest_put "$prov" 755
  write_session   | guest_put "$sess" 755
  guest_has "$prov" || die "Could not place $prov inside the $ALIAS container."
  guest_has "$sess" || die "Could not place $sess inside the $ALIAS container."
  ok "Files present inside the container"

  info "Provisioning $KEY (this takes a while)"
  proot-distro login "$ALIAS" -- /bin/sh "$prov" "$USER_NAME" "$DE" </dev/null
  proot-distro login "$ALIAS" -- /bin/sh -c 'rm -f "$1"' sh "$prov" || true
  guest_has "$sess" || die "$sess disappeared during provisioning."
  ok "Guest provisioned"
}

# ---------- host side: start_<distro>.sh ----------
write_start_script() {
  local out="$HOME/start_${KEY}.sh"
  {
    printf '#!%s/bin/bash\n' "$PREFIX"
    printf 'ALIAS=%q; KEY=%q; DE=%q; RES=%q; GUEST_USER=%q\n' "$ALIAS" "$KEY" "$DE" "$RES" "$USER_NAME"
    cat <<'RB_START'
set -Eeuo pipefail
PASS=""; STOP=0; RESTART=0
STATE="$HOME/.rombooter"; LOG="$STATE/$KEY.log"; mkdir -p "$STATE"

while (($#)); do
  case $1 in
    --res)     RES=${2:?}; shift 2 ;;
    --pass)    PASS=${2:?}; shift 2 ;;
    --de)      DE=${2:?}; shift 2 ;;
    --stop)    STOP=1; shift ;;
    --restart) RESTART=1; shift ;;
    *) echo "usage: $0 [--res WxH] [--pass P] [--de xfce|i3] [--stop] [--restart]" >&2; exit 2 ;;
  esac
done
[[ $RES =~ ^[0-9]{3,4}x[0-9]{3,4}$ ]] || { echo "bad --res" >&2; exit 2; }
[[ $DE == xfce || $DE == i3 ]]        || { echo "bad --de" >&2; exit 2; }
[[ -z $PASS || $PASS =~ ^[A-Za-z0-9]{6,8}$ ]] || { echo "bad --pass (6-8 alphanumeric)" >&2; exit 2; }

port_open()    { (exec 3<>/dev/tcp/127.0.0.1/5901) 2>/dev/null; }
stop_session() { pkill -f 'Xvnc :1' 2>/dev/null || true; pkill -f 'rb-session' 2>/dev/null || true; }

if ((STOP)); then stop_session; echo "stopped"; exit 0; fi
if port_open; then
  if ((RESTART)); then stop_session; sleep 2; else echo "READY 5901 (already running)"; exit 0; fi
fi

if command -v termux-wake-lock >/dev/null 2>&1; then termux-wake-lock || true; fi

# Audio: PulseAudio on Termux side, loopback-only TCP; guest uses PULSE_SERVER=127.0.0.1
if ! pulseaudio --check 2>/dev/null; then
  pulseaudio --start --exit-idle-time=-1 \
    --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1" >/dev/null 2>&1 || true
fi
pactl list short sinks 2>/dev/null | grep -q . || pactl load-module module-sles-sink >/dev/null 2>&1 || true

nohup proot-distro login "$ALIAS" --user "$GUEST_USER" -- \
  /usr/local/bin/rb-session "$RES" "$PASS" "$DE" >"$LOG" 2>&1 &
GPID=$!

for _ in $(seq 1 120); do
  if port_open; then echo "READY 5901"; exit 0; fi
  if ! kill -0 "$GPID" 2>/dev/null; then
    echo "session exited early, log: $LOG" >&2; tail -n 20 "$LOG" >&2; exit 1
  fi
  sleep 1
done
echo "timeout waiting for 127.0.0.1:5901, log: $LOG" >&2
exit 1
RB_START
  } >"$out"
  chmod 700 "$out"
  ok "Created $out"
}

summary() {
  printf '\n%s%s ready.%s\n' "$C_G" "$KEY" "$C_X"
  printf '  Start : %s~/start_%s.sh --res %s%s\n' "$C_C" "$KEY" "$RES" "$C_X"
  printf '  Stop  : %s~/start_%s.sh --stop%s\n' "$C_C" "$KEY" "$C_X"
  printf '  VNC   : 127.0.0.1:5901 (loopback only, set a password via --pass, 6-8 chars)\n'
  printf '  Log   : ~/.rombooter/%s.log\n' "$KEY"
}

main() {
  parse_args "$@"
  preflight
  [[ -n $DISTRO ]] || menu
  resolve_distro
  confirm
  install_host_deps
  enable_termux_external
  prepare_distro
  install_distro
  provision
  write_start_script
  summary
}
main "$@"
