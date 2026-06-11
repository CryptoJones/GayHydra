#!/usr/bin/env bash
# Regenerate the committed hint-recall corpus objects. Running this is a
# deliberate baseline-update event (new toolchain → new codegen → new
# recall numbers), NOT part of CI — CI consumes the committed .o files so
# the metric is pinned to exact bytes.
#
# Matrix: {gcc, clang} × {x86_64, aarch64} × {O0, O2} → objects/<cc>-<arch>-<opt>.o
# Cross legs need: g++-aarch64-linux-gnu, clang (with its integrated
# aarch64 backend). No sysroot is needed — corpus.cpp is header-free.

set -euo pipefail
cd "$(dirname "$0")"
mkdir -p objects

build() { # name cc arch opt extra-flags...
  local name=$1 cc=$2 arch=$3 opt=$4; shift 4
  local out="objects/${name}-${arch}-${opt}.o"
  "$cc" "$@" -"$opt" -c -fno-exceptions corpus.cpp -o "$out"
  echo "built $out"
}

build gcc   g++                   x86_64  O0
build gcc   g++                   x86_64  O2
build gcc   aarch64-linux-gnu-g++ aarch64 O0
build gcc   aarch64-linux-gnu-g++ aarch64 O2
build clang clang++ x86_64  O0 --target=x86_64-linux-gnu
build clang clang++ x86_64  O2 --target=x86_64-linux-gnu
build clang clang++ aarch64 O0 --target=aarch64-linux-gnu
build clang clang++ aarch64 O2 --target=aarch64-linux-gnu

echo "Done. Re-run the recall counter and update baseline.json deliberately."
