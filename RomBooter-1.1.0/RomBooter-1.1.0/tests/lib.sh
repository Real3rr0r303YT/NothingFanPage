# shellcheck shell=bash
# Shared helpers for the RomBooter test scripts.
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SETUP="$ROOT/rombooter-setup.sh"
PASSES=0; FAILS=0
pass() { PASSES=$((PASSES + 1)); printf '  ok   %s\n' "$*"; }
fail() { FAILS=$((FAILS + 1)); printf '  FAIL %s\n' "$*"; }
check() { # description command...
  local d=$1; shift
  if "$@"; then pass "$d"; else fail "$d"; fi
}
finish() {
  printf '\n%d passed, %d failed\n' "$PASSES" "$FAILS"
  ((FAILS == 0))
}

# gen DISTRO DE OUTDIR [USER] -> OUTDIR/{rb-provision.sh,rb-session,start_<key>.sh}
gen() {
  local distro=$1 de=$2 out=$3 user=${4:-rom}
  mkdir -p "$out"
  (
    set -e
    # shellcheck source=../rombooter-setup.sh
    source "$SETUP"
    DISTRO=$distro; DE=$de; USER_NAME=$user; RES=1280x608
    PREFIX=/usr HOME=$out
    resolve_distro
    write_provision >"$out/rb-provision.sh"
    write_session >"$out/rb-session"
    write_start_script >/dev/null
  ) && [[ -s $out/rb-provision.sh && -s $out/rb-session && -s $out/start_${distro/archlinux/arch}.sh ]]
}
