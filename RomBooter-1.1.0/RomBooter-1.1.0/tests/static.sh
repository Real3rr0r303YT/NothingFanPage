#!/usr/bin/env bash
# Static checks: shellcheck + syntax of the setup script and of every generated script, all 7 distros.
set -uo pipefail
source "$(dirname "$0")/lib.sh"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

echo "setup script"
check "bash -n rombooter-setup.sh" bash -n "$SETUP"
check "shellcheck rombooter-setup.sh" shellcheck -x "$SETUP"

for d in arch gentoo debian ubuntu alpine kali fedora; do
  echo "$d"
  de="xfce,i3,sway,phosh"; [[ $d == gentoo ]] && de="xfce,i3,sway"
  if ! gen "$d" "$de" "$tmp/$d" 2>"$tmp/$d.err"; then fail "generate ($(cat "$tmp/$d.err"))"; continue; fi
  key=$d
  for sh in dash "busybox sh"; do
    command -v "${sh%% *}" >/dev/null || continue
    check "$sh -n rb-provision.sh" $sh -n "$tmp/$d/rb-provision.sh"
    check "$sh -n rb-session" $sh -n "$tmp/$d/rb-session"
  done
  check "shellcheck rb-provision.sh" shellcheck -s sh "$tmp/$d/rb-provision.sh"
  check "shellcheck rb-session" shellcheck -s sh "$tmp/$d/rb-session"
  check "bash -n start_$key.sh" bash -n "$tmp/$d/start_$key.sh"
  check "shellcheck start_$key.sh" shellcheck -s bash "$tmp/$d/start_$key.sh"
  check "start script defaults to the first session" grep -q "DE=xfce;" "$tmp/$d/start_$key.sh"
done

echo "argument validation (setup)"
expect_die() { # description args...
  local d=$1; shift
  if (source "$SETUP"; parse_args "$@") >/dev/null 2>&1; then fail "$d"; else pass "$d"; fi
}
expect_ok() {
  local d=$1; shift
  if (source "$SETUP"; parse_args "$@") >/dev/null 2>&1; then pass "$d"; else fail "$d"; fi
}
expect_die "--de without value" --distro debian --de
expect_die "--de followed by option" --de --yes
expect_die "--de unknown session" --de kde
expect_die "--de duplicate" --de xfce,xfce
expect_die "--de trailing comma" --de xfce,
expect_die "--res garbage" --res 12x
expect_die "--res too small" --res 100x100
expect_die "--user root" --user root
expect_die "unknown option" --frobnicate
expect_ok  "--de=xfce,sway --res=1280x576" --de=xfce,sway --res=1280x576
expect_ok  "--de I3 (case-insensitive)" --de I3
if (source "$SETUP"; DISTRO=gentoo DE=phosh; resolve_distro) >/dev/null 2>&1; then
  fail "phosh on gentoo is rejected"; else pass "phosh on gentoo is rejected"; fi

finish
