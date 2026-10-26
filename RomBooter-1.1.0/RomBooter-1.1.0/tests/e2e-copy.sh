#!/usr/bin/env bash
# Copy-in test for all 7 distros (docker, no package mirrors needed): runs the setup's real
# install_distro + copy_in (stdin streaming, atomic rename, checksum verification) against each
# distro's container image, then checks the copied scripts with that distro's own /bin/sh.
set -uo pipefail
source "$(dirname "$0")/lib.sh"
DISTROS=("$@"); ((${#DISTROS[@]})) || DISTROS=(arch gentoo debian ubuntu alpine kali fedora)
WORK=$(mktemp -d); export PATH="$ROOT/tests/e2e:$PATH"
trap 'rm -rf "$WORK"' EXIT

for d in "${DISTROS[@]}"; do
  echo "$d"
  de="xfce,i3,sway,phosh"; [[ $d == gentoo ]] && de="xfce,i3,sway"
  out=$(
    source "$SETUP"
    DISTRO=$d; DE=$de; USER_NAME=rom; PREFIX=/usr; HOME=$WORK; TMPDIR=$WORK
    resolve_distro
    echo "ALIAS=$ALIAS"
    install_distro && copy_in
  ) 2>&1
  rc=$?; alias=$(sed -n 's/^ALIAS=//p' <<<"$out")
  if ((rc)); then fail "install + copy-in: $(tail -n 3 <<<"$out")"; continue; fi
  pass "install + copy-in with checksum verification"
  pd() { proot-distro login "$alias" -- "$@"; }
  check "rb-provision.sh is executable" pd /bin/sh -c 'test -x /root/rb-provision.sh'
  check "rb-session is executable" pd /bin/sh -c 'test -x /usr/local/bin/rb-session'
  check "rb-provision.sh parses under the guest /bin/sh" pd /bin/sh -n /root/rb-provision.sh
  check "rb-session parses under the guest /bin/sh" pd /bin/sh -n /usr/local/bin/rb-session
  check "rb-provision.sh rejects a missing user" bash -c '! proot-distro login "$1" -- /bin/sh /root/rb-provision.sh 2>/dev/null' _ "$alias"
  check "rb-session --list runs (empty registry)" pd /bin/sh /usr/local/bin/rb-session --list
  msg=$(pd /bin/sh /usr/local/bin/rb-session --de sway 2>&1)
  check "rb-session explains a missing session" grep -q "not installed" <<<"$msg"
  msg=$(pd /bin/sh /usr/local/bin/rb-session --res 12 2>&1)
  check "rb-session rejects a bad resolution" grep -q "bad resolution" <<<"$msg"
  # copying again over existing files must be idempotent
  out=$( source "$SETUP"; DISTRO=$d; DE=$de; PREFIX=/usr; HOME=$WORK; TMPDIR=$WORK; resolve_distro; copy_in ) 2>&1
  check "second copy-in (overwrite) succeeds" test $? -eq 0
  docker rm -f "rb-e2e-$alias" >/dev/null 2>&1
done
finish
