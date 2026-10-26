#!/usr/bin/env bash
# Behaviour tests for the generated start_<distro>.sh (argument handling, password transport,
# restart/stop logic, early-exit reporting) with a fake proot-distro and a fake VNC server.
set -uo pipefail
source "$(dirname "$0")/lib.sh"
tmp=$(mktemp -d)
export FAKE_DIR="$tmp/fake" RB_VNC_PORT=$((20000 + RANDOM % 20000)) RB_START_TIMEOUT=8
export PATH="$ROOT/tests/fixtures:$PATH" HOME="$tmp/home"
mkdir -p "$FAKE_DIR" "$HOME"
gen debian xfce,i3 "$HOME" >/dev/null
S="$HOME/start_debian.sh"
cleanup() { bash "$S" --stop >/dev/null 2>&1; rm -rf "$tmp"; }
trap cleanup EXIT

run() { OUT=$(bash "$S" "$@" 2>"$tmp/err"); RC=$?; ERR=$(cat "$tmp/err"); }
expect_rc() { # rc description args...
  local want=$1 d=$2; shift 2
  run "$@"
  if [[ $RC == "$want" ]]; then pass "$d (exit $RC)"; else fail "$d: want exit $want, got $RC; stderr: $ERR"; fi
}

echo "argument validation"
expect_rc 2 "--pass without value"          --res 1280x576 --pass
expect_rc 2 "--pass followed by an option"  --pass --de xfce
expect_rc 2 "--pass too short"              --pass abc
expect_rc 2 "--pass with a space"           --pass "abc def12"
expect_rc 2 "--res malformed"               --res 1280
expect_rc 2 "--res out of range"            --res 99999x1
expect_rc 2 "--de with bad characters"      --de 'x;rm'
expect_rc 2 "unknown argument"              --bogus
check "error names the offending option" grep -q "unknown argument '--bogus'" <<<"$ERR"

echo "not provisioned"
FAKE_UNPROVISIONED=1 expect_rc 4 "rb-session missing in the guest" --pass Abc12345

echo "start"
rm -f "$FAKE_DIR/conns"
expect_rc 0 "start with app-style arguments" --res 1280x576 --pass Abc12345 --de xfce
check "prints READY" grep -qx "READY $RB_VNC_PORT" <<<"$OUT"
check "password reaches the guest via stdin" grep -qx Abc12345 "$FAKE_DIR/pass"
check "password is not on the guest command line" bash -c '! grep -q Abc12345 "$1"' _ "$FAKE_DIR/argv"
check "guest gets --res/--de/--port" grep -q -- "--res 1280x576 --de xfce --port $RB_VNC_PORT --pass-stdin" "$FAKE_DIR/argv"
check "at most one readiness probe (Xvnc blacklists unauthenticated connections)" \
  bash -c '(( $(wc -l <"$1") <= 1 ))' _ "$FAKE_DIR/conns"
check "session file records the process group" bash -c 'read -r k p f <"$1" && [[ $k == debian && $p =~ ^[0-9]+$ ]]' _ "$HOME/.rombooter/session"

expect_rc 0 "second start with same arguments" --res 1280x576 --pass Abc12345 --de xfce
check "reuses the running session" grep -q "already running" <<<"$OUT"

rm -f "$FAKE_DIR/pass"
expect_rc 0 "start with a different password" --res 1280x576 --pass Xyz98765 --de xfce
check "restarts instead of reusing (auth would fail)" grep -qx Xyz98765 "$FAKE_DIR/pass"

expect_rc 0 "--pass=value form and empty password" --res=1280x576 --pass= --de=i3
check "empty password reaches guest as empty line" bash -c '[[ ! -s $1 || $(cat "$1") == "" ]]' _ "$FAKE_DIR/pass"

expect_rc 0 "--status while running" --status
check "status reports RUNNING" grep -q "^RUNNING debian" <<<"$OUT"

echo "stop"
expect_rc 0 "--stop" --stop
check "port closed after stop" bash -c '! (exec 3<>/dev/tcp/127.0.0.1/$1) 2>/dev/null' _ "$RB_VNC_PORT"
check "session file removed" test ! -e "$HOME/.rombooter/session"
expect_rc 0 "--stop when nothing runs" --stop

echo "failure reporting"
FAKE_MODE=die expect_rc 1 "session dies during startup" --pass Abc12345
check "stderr carries the guest log tail" grep -q "Xvnc exited during startup" <<<"$ERR"
FAKE_MODE=eof expect_rc 3 "server accepts but never speaks RFB (EOF case)" --pass Abc12345
check "no READY for a silent server" bash -c '! grep -q READY <<<"$1"' _ "$OUT"
bash "$S" --stop >/dev/null 2>&1

finish
