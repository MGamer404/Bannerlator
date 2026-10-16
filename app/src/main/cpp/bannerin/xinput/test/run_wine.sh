#!/usr/bin/env bash
# run_wine.sh <dlls dir> <tests dir> <feeder binary>
#
# The BANNERIN-XInput end-to-end test under Wine (CI, x86_64 Linux runner with wine64 + wine32):
# for each case a fresh slot directory, the feeder publishing into it with the app's writer
# engine, and xinput_test.exe reading it through the DLLs placed next to it (forced native with
# WINEDLLOVERRIDES, the same override the app sets). Cases: x86_64 DLLs with every DOS path form
# (wine_get_dos_file_name, \\?\unix, Z:), and the i386 DLLs under WoW64.
set -uo pipefail

DLLS="$(cd "${1:?dlls dir}" && pwd)"
TESTS="$(cd "${2:?tests dir}" && pwd)"
FEEDER="$(cd "$(dirname "${3:?feeder}")" && pwd)/$(basename "$3")"
: "${WINEPREFIX:=$HOME/.wine-bxi}"
export WINEPREFIX WINEDEBUG="${WINEDEBUG:--all}"
OVR="xinput1_1,xinput1_2,xinput1_3,xinput1_4,xinput9_1_0,xinputuap=n;mscoree,mshtml="
WINE="${WINE:-wine}"

if [ ! -f "$WINEPREFIX/system.reg" ]; then
  echo "== creating the Wine prefix"
  WINEDLLOVERRIDES="mscoree,mshtml=" timeout 300 "$WINE" wineboot -i >/dev/null 2>&1
  timeout 60 wineserver -w || true
fi
"$WINE" --version

fails=0
run_case() { # run_case <arch dir> <path mode>
  local arch="$1" mode="$2" d port rc frc
  d="$(mktemp -d)"
  mkdir -p "$d/slots" "$d/bin"
  cp "$DLLS/$arch/"*.dll "$TESTS/$arch/xinput_test.exe" "$d/bin/"
  port=$((40000 + RANDOM % 20000))
  echo
  echo "======== $arch DLLs, paths: $mode, rumble port $port"
  "$FEEDER" script "$d/slots" "$d/ctl" "$port" >"$d/feeder.log" 2>&1 &
  local fpid=$!
  for _ in $(seq 1 200); do [ -f "$d/ctl.ready" ] && break; sleep 0.05; done
  [ -f "$d/ctl.ready" ] || { echo "feeder did not start"; cat "$d/feeder.log"; fails=$((fails + 1)); return; }
  local ctl_dos="Z:$(echo "$d/ctl" | tr / '\\')"
  FAKE_EVDEV_MEMFD_PATHS="0=$d/slots/pad0;1=$d/slots/pad1;2=$d/slots/pad2" \
  BANNERIN_XINPUT_PATHMODE="$mode" BANNERIN_XINPUT_RUMBLE_PORT="$port" WINEDLLOVERRIDES="$OVR" \
    timeout 240 "$WINE" "$d/bin/xinput_test.exe" "$ctl_dos" 2>"$d/stderr.log"
  rc=$?
  # the test writes step 999 on its way out; make sure the feeder stops even if it did not
  [ "$rc" = 0 ] || printf '%08u %08u\n' 999 $((99999999 - 999)) >"$d/ctl"
  wait "$fpid"; frc=$?
  echo "---- feeder"
  cat "$d/feeder.log"
  echo "---- DLL diagnostics (stderr)"
  grep -a 'BANNERIN-XInput' "$d/stderr.log" | head -40 || true
  if [ "$rc" != 0 ] || [ "$frc" != 0 ]; then
    echo "!!!! $arch/$mode failed: test exit $rc, feeder exit $frc"
    echo "---- full stderr (tail)"; tail -60 "$d/stderr.log"
    fails=$((fails + 1))
  else
    echo "==== $arch/$mode passed"
  fi
  timeout 30 wineserver -w || true
}

run_case x86_64 auto
run_case x86_64 unix
run_case x86_64 z
run_case i386 auto

echo
echo "== $fails case(s) failed"
[ "$fails" = 0 ]
