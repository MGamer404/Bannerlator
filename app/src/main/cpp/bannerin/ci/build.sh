#!/usr/bin/env bash
# Build and verify the BANNERIN transport crate. Used by .github/workflows/_build.yml (before the
# Gradle/CMake build) and by .github/workflows/bannerin-check.yml.
#
#   build.sh test     host unit + multi-process scenario tests (cargo test, blocking)
#   build.sh loom     loom model check of the publish/read protocol
#   build.sh android  libbannerin_guest.a + libbannerin.so for aarch64-linux-android, link probe
#                     (needs NDK_BIN = <ndk>/toolchains/llvm/prebuilt/linux-x86_64/bin)
#   build.sh glibc    libbannerin_guest.a for aarch64-unknown-linux-gnu, link probe
#                     (needs aarch64-linux-gnu-gcc and/or -g++; NDK_BIN optional, for llvm-ar)
#
# The probes link a shared object referencing every bannerin_* function, the same way the
# interposers link the archive, and fail the build if it needs anything beyond libc/libdl/
# pthread (liblog on Android), imports an unwinder, or exports any symbol of the library.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

say() { printf '== bannerin: %s\n' "$*"; }
die() { printf '::error::bannerin: %s\n' "$*" >&2; exit 1; }

ar_tool() {
  if [ -n "${NDK_BIN:-}" ] && [ -x "$NDK_BIN/llvm-ar" ]; then echo "$NDK_BIN/llvm-ar"
  elif command -v aarch64-linux-gnu-ar >/dev/null; then echo aarch64-linux-gnu-ar
  else die "no archiver (llvm-ar or aarch64-linux-gnu-ar) found"; fi
}

# The staticlib carries its own copy of Rust's compiler builtins. Drop them, so the interposer's
# link resolves those helpers from its own toolchain runtime exactly as before, and the archive
# contributes nothing but the transport itself.
strip_builtins() {
  local a="$1" ar; ar="$(ar_tool)"
  local i=0
  while "$ar" t "$a" | grep -q 'compiler_builtins'; do
    "$ar" t "$a" | grep 'compiler_builtins' | head -n 400 | xargs "$ar" d "$a"
    i=$((i + 1)); [ "$i" -lt 50 ] || die "could not strip compiler_builtins from $a"
  done
  "$ar" s "$a" 2>/dev/null || true
  say "archive members of $(basename "$(dirname "$(dirname "$a")")")/$(basename "$a"):"
  "$ar" t "$a" | sed 's/^/     /'
}

# check_probe <readelf> <nm> <so> <allowed NEEDED...>
check_probe() {
  local readelf="$1" nm="$2" so="$3"; shift 3
  local need; need="$("$readelf" -d "$so" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p')"
  say "$(basename "$so") NEEDED: $(echo $need)"
  local n ok a
  for n in $need; do
    ok=0
    for a in "$@"; do [ "$n" = "$a" ] && ok=1; done
    [ "$ok" = 1 ] || die "$(basename "$so") needs $n (allowed: $*)"
  done
  local exports; exports="$("$nm" -D --defined-only "$so" | awk '{print $NF}')"
  say "$(basename "$so") exports: $(echo $exports)"
  local bad; bad="$(printf '%s\n' "$exports" | grep -E '^(bannerin_|rust_|__rust|_ZN|_R[A-Z0-9])' | grep -vx 'bannerin_linkprobe_entry' || true)"
  [ -z "$bad" ] || die "$(basename "$so") exports library symbols: $bad"
  printf '%s\n' "$exports" | grep -qx 'bannerin_linkprobe_entry' || die "probe entry not exported"
  local imports; imports="$("$nm" -D --undefined-only "$so" | awk '{print $NF}')"
  say "$(basename "$so") imports: $(echo $imports)"
  if printf '%s\n' "$imports" | grep -qE '^(_Unwind_|__gcc_personality|rust_eh_personality)'; then
    die "$(basename "$so") imports unwinder symbols"
  fi
}

case "${1:-}" in
test)
  say "host tests"
  cargo test --workspace --no-fail-fast -- --test-threads=1
  ;;
loom)
  say "loom model check"
  (cd loom && LOOM_MAX_PREEMPTIONS="${LOOM_MAX_PREEMPTIONS:-2}" cargo test --release --no-fail-fast)
  ;;
android)
  : "${NDK_BIN:?NDK_BIN must point at <ndk>/toolchains/llvm/prebuilt/linux-x86_64/bin}"
  CC="$NDK_BIN/aarch64-linux-android26-clang"
  [ -x "$CC" ] || die "NDK clang missing: $CC"
  export CC_aarch64_linux_android="$CC"
  export AR_aarch64_linux_android="$NDK_BIN/llvm-ar"
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC"
  say "guest static library + JNI writer (aarch64-linux-android)"
  cargo build --release --target aarch64-linux-android -p bannerin-guest -p bannerin-jni
  A=target/aarch64-linux-android/release/libbannerin_guest.a
  SO=target/aarch64-linux-android/release/libbannerin.so
  test -s "$A" && test -s "$SO"
  strip_builtins "$A"
  say "undefined symbols the archive needs from the link:"
  "$NDK_BIN/llvm-nm" -u "$A" | awk 'NF {print $NF}' | sort -u | tr '\n' ' '; echo
  say "link probe (bionic, as libfakeinput.so links it)"
  "$CC" -shared -fPIC -O2 -Wall -Wextra -Werror -fvisibility=hidden -Iinclude \
    -Wl,--no-undefined -Wl,--exclude-libs,libbannerin_guest.a \
    -o target/probe-android.so ci/linkprobe.c "$A" -llog -ldl
  "$NDK_BIN/clang++" --target=aarch64-linux-android26 -std=c++17 -Wall -Wextra -Werror -fsyntax-only \
    -x c++ -Iinclude ci/linkprobe.c
  check_probe "$NDK_BIN/llvm-readelf" "$NDK_BIN/llvm-nm" target/probe-android.so libc.so libdl.so liblog.so libm.so
  say "JNI writer exports"
  JNI="$("$NDK_BIN/llvm-nm" -D --defined-only "$SO" | awk '{print $NF}')"
  for f in Prepare Release Open Publish SetOverlay Reset Unplug Stat; do
    printf '%s\n' "$JNI" | grep -qx "Java_com_winlator_star_inputcontrols_BannerinWriter_native$f" \
      || die "libbannerin.so does not export native$f"
  done
  if printf '%s\n' "$JNI" | grep -q '^bannerin_'; then die "libbannerin.so exports guest symbols"; fi
  "$NDK_BIN/llvm-readelf" -d "$SO" | grep -E 'NEEDED|SONAME' | sed 's/^/     /'
  ls -l "$A" "$SO"
  ;;
glibc)
  say "guest static library (aarch64-unknown-linux-gnu)"
  cargo build --release --target aarch64-unknown-linux-gnu -p bannerin-guest
  A=target/aarch64-unknown-linux-gnu/release/libbannerin_guest.a
  test -s "$A"
  strip_builtins "$A"
  NM=aarch64-linux-gnu-nm
  if [ -n "${NDK_BIN:-}" ] && [ -x "$NDK_BIN/llvm-nm" ]; then NM="$NDK_BIN/llvm-nm"; fi
  if command -v "$NM" >/dev/null; then
    say "undefined symbols the archive needs from the link:"
    "$NM" -u "$A" | awk 'NF {print $NF}' | sort -u | tr '\n' ' '; echo
  fi
  probed=0
  if command -v aarch64-linux-gnu-gcc >/dev/null; then
    say "link probe (glibc, C)"
    aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -Wextra -Werror -fvisibility=hidden -pthread -static-libgcc \
      -Iinclude -Wl,--no-undefined -Wl,--exclude-libs,ALL \
      -o target/probe-glibc.so ci/linkprobe.c "$A" -ldl
    check_probe aarch64-linux-gnu-readelf aarch64-linux-gnu-nm target/probe-glibc.so \
      libc.so.6 libdl.so.2 libpthread.so.0 ld-linux-aarch64.so.1
    probed=1
  fi
  if command -v aarch64-linux-gnu-g++ >/dev/null; then
    say "link probe (glibc, C++ with static C++ runtime, as fakeinput_steam.cpp is built)"
    aarch64-linux-gnu-g++ -shared -fPIC -O2 -Wall -Wextra -Werror -fvisibility=hidden -pthread -std=c++17 \
      -static-libstdc++ -static-libgcc -Iinclude -Wl,--no-undefined -Wl,--exclude-libs,ALL \
      -x c++ -o target/probe-glibc-cxx.so ci/linkprobe.c -x none "$A" -ldl
    check_probe aarch64-linux-gnu-readelf aarch64-linux-gnu-nm target/probe-glibc-cxx.so \
      libc.so.6 libdl.so.2 libpthread.so.0 libm.so.6 ld-linux-aarch64.so.1
    probed=1
  fi
  [ "$probed" = 1 ] || die "no aarch64-linux-gnu compiler for the glibc link probe"
  ls -l "$A"
  ;;
*)
  die "usage: $0 test|loom|android|glibc"
  ;;
esac
