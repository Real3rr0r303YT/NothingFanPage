#!/usr/bin/env bash
# End-to-end test on x86_64 docker images (needs docker + java 17+, network for packages).
# Runs the REAL setup functions (install, copy-in, provisioning, start-script generation) against
# containers that stand in for proot-distro, then boots each session with the generated
# start script and performs the app's VNC handshake with the app-style password.
#   tests/e2e-docker.sh [distro[:sessions] ...]      default: all except gentoo
set -uo pipefail
source "$(dirname "$0")/lib.sh"
TARGETS=("$@")
((${#TARGETS[@]})) || TARGETS=(debian:xfce,i3,sway ubuntu:i3 kali:i3 alpine:i3,sway arch:i3 fedora:i3)
WORK=$(mktemp -d); export PATH="$ROOT/tests/e2e:$PATH" RB_VNC_PORT=25901
[[ -f /root/.ccr/ca-bundle.crt && -z ${RB_E2E_CA:-} ]] && export RB_E2E_CA=/root/.ccr/ca-bundle.crt
PASSWORD=Ab3dEf7h
mkdir -p "$WORK/logs"

setup_one() { # distro sessions
  local d=$1 de=$2 home="$WORK/$1"
  mkdir -p "$home"
  (
    source "$SETUP"
    DISTRO=$d; DE=$de; USER_NAME=rom; RES=1280x608; ASSUME_YES=1
    PREFIX=/usr HOME=$home TMPDIR=$WORK
    resolve_distro
    install_distro && provision && write_start_script
  )
}

echo "provisioning in parallel: ${TARGETS[*]} (logs in $WORK/logs)"
declare -A PIDS
for t in "${TARGETS[@]}"; do
  setup_one "${t%%:*}" "${t#*:}" >"$WORK/logs/${t%%:*}.setup.log" 2>&1 &
  PIDS[$t]=$!
done
for t in "${TARGETS[@]}"; do
  d=${t%%:*}
  if wait "${PIDS[$t]}"; then pass "$d: install + copy-in + provision ($(tail -n 1 "$WORK/logs/$d.setup.log"))"
  else fail "$d: setup failed:"; tail -n 15 "$WORK/logs/$d.setup.log" | sed 's/^/        /'; fi
done

probe() { java "$ROOT/tests/e2e/RfbProbe.java" "$RB_VNC_PORT" "$@" 2>&1; }
start() { HOME="$WORK/$d" timeout 240 bash "$S" --res 1024x576 --pass "$PASSWORD" --de "$s" "$@" >"$WORK/logs/$d.$s.start" 2>&1; }

# Failure modes behind "Server refused:" / EOFException and the app's connection pattern (x11 only).
robustness_x11() {
  local i out
  # 1. Xvnc counts every connection closed before login. Ten boots, each with a few readiness
  # probes (start script + app fallback) and a wrong login, followed by the app's login, must work.
  local okc=0
  for i in $(seq 1 10); do
    for _ in 1 2 3; do (exec 3<>"/dev/tcp/127.0.0.1/$RB_VNC_PORT"; head -c 12 <&3 >/dev/null) 2>/dev/null; done
    probe wrongpw1 >/dev/null
    probe "$PASSWORD" | grep -q '^OK' && okc=$((okc + 1))
  done
  ((okc == 10)) && pass "$d/$s: 10 boots with probes + a failed login each never get blacklisted" \
    || fail "$d/$s: only $okc/10 logins after probes succeeded"
  # 2. Many failed logins in a row (another app guessing): server refuses, then recovers by itself.
  for i in $(seq 1 20); do probe wrongpw1 >/dev/null; done
  out=$(probe "$PASSWORD")
  grep -q 'refused' <<<"$out" && pass "$d/$s: blacklisted after 20 failed logins (the 'Server refused:' case)" \
    || fail "$d/$s: expected refusal while blacklisted, got: $(tail -n 1 <<<"$out")"
  sleep 3
  probe "$PASSWORD" | grep -q '^OK' && pass "$d/$s: blacklist expires after a few seconds" \
    || fail "$d/$s: still refused after BlacklistTimeout"
  # 3. What the app does on a refusal: start script --restart, then log in.
  for i in $(seq 1 21); do probe wrongpw1 >/dev/null; done
  if start --restart && probe "$PASSWORD" | grep -q '^OK'; then pass "$d/$s: --restart clears a blacklisted server"
  else fail "$d/$s: --restart recovery"; tail -n 5 "$WORK/logs/$d.$s.start" | sed 's/^/        /'; fi
}

# A 1.0.x session (no session file, empty password file) is replaced, not reused.
legacy_x11() {
  local c="rb-e2e-${ALIAS_OF[$d]}"
  HOME="$WORK/$d" bash "$S" --stop >/dev/null 2>&1
  docker exec -u rom "$c" sh -c ': >/tmp/legacy-passwd; (Xvnc :1 -geometry 800x600 -rfbport '"$RB_VNC_PORT"' -localhost \
    -SecurityTypes VncAuth -rfbauth /tmp/legacy-passwd >/dev/null 2>&1 &)'
  sleep 3
  probe "$PASSWORD" | grep -q '^OK' && fail "$d/$s: legacy server unexpectedly accepted the login" \
    || pass "$d/$s: legacy 1.0.x server (empty passwd) rejects the app, as reported"
  if start && probe "$PASSWORD" | grep -q '^OK'; then pass "$d/$s: start script replaces the legacy server"
  else fail "$d/$s: legacy server not replaced"; tail -n 5 "$WORK/logs/$d.$s.start" | sed 's/^/        /'; fi
}

# Display :1 owned by a foreign X server in the same network namespace (Termux vncserver, another
# distro): only the abstract socket is shared, there is no lock file to see.
foreign_display_x11() {
  local c="rb-e2e-${ALIAS_OF[$d]}"
  HOME="$WORK/$d" bash "$S" --stop >/dev/null 2>&1
  docker commit "$c" rb-e2e-foreign-img >/dev/null &&
    docker run -d --rm --network host --name rb-e2e-foreign rb-e2e-foreign-img \
      sh -c 'Xvnc :1 -rfbport 25999 -localhost -SecurityTypes None' >/dev/null
  sleep 3
  if start && probe "$PASSWORD" | grep -q '^OK'; then
    pass "$d/$s: session starts while :1 is taken ($(grep -o 'on display :[0-9]' "$WORK/$d/.rombooter/$d.log" | tail -n 1))"
  else fail "$d/$s: busy display :1"; tail -n 8 "$WORK/$d/.rombooter/$d.log" | sed 's/^/        /'; fi
  docker rm -f rb-e2e-foreign >/dev/null 2>&1; docker rmi rb-e2e-foreign-img >/dev/null 2>&1
}

declare -A ALIAS_OF=([arch]=archlinux [gentoo]=gentoo [debian]=debian [ubuntu]=ubuntu [alpine]=alpine [kali]=kali [fedora]=fedora)

# Sessions share host networking, so boot them one at a time.
declare -A x11_done
for t in "${TARGETS[@]}"; do
  d=${t%%:*}; key=$d; S="$WORK/$d/start_$key.sh"
  [[ -x $S ]] || { fail "$d: no start script"; continue; }
  IFS=, read -ra sessions <<<"${t#*:}"
  for s in "${sessions[@]}"; do
    if start; then
      pass "$d/$s: start script READY"
      if java "$ROOT/tests/e2e/RfbProbe.java" "$RB_VNC_PORT" "$PASSWORD" 1024x576 >"$WORK/logs/$d.$s.probe" 2>&1; then
        pass "$d/$s: VNC handshake + first frame ($(grep -h '^security' "$WORK/logs/$d.$s.probe"))"
      else
        fail "$d/$s: VNC probe"; sed 's/^/        /' "$WORK/logs/$d.$s.probe" | tail -n 8
      fi
      if [[ $s != sway && $s != phosh ]]; then
        java "$ROOT/tests/e2e/RfbProbe.java" "$RB_VNC_PORT" wrongpw1 >/dev/null 2>&1 \
          && fail "$d/$s: wrong password accepted" || pass "$d/$s: wrong password rejected"
        if [[ -z ${x11_done[$d]:-} ]]; then
          x11_done[$d]=1
          robustness_x11; legacy_x11; foreign_display_x11
        fi
      fi
    else
      fail "$d/$s: start script"; sed 's/^/        /' "$WORK/logs/$d.$s.start" | tail -n 12
    fi
    HOME="$WORK/$d" bash "$S" --stop >/dev/null 2>&1 \
      && pass "$d/$s: stop" || fail "$d/$s: stop left port $RB_VNC_PORT open"
  done
done

[[ -n ${KEEP:-} ]] || for t in "${TARGETS[@]}"; do d=${t%%:*}; [[ $d == arch ]] && d=archlinux; docker rm -f "rb-e2e-$d" >/dev/null 2>&1; done
echo "logs: $WORK/logs"
finish
