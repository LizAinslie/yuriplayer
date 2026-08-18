#!/usr/bin/env bash
# Cross-compile a slim FFmpeg binary per Android ABI with the NDK.
# Outputs: $FFMPEG_ASSETS_DIR/<abi>/ffmpeg
#
# Requires: ANDROID_NDK_HOME (or ANDROID_NDK), curl, make, bash.
# Gradle only re-runs this when constraints.env / build.sh change (and outputs missing).

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
set -a
# shellcheck source=constraints.env
source "$ROOT/constraints.env"
set +a

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}"
if [[ -z "${NDK}" ]]; then
  if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
    NDK="$(ls -d "$ANDROID_HOME/ndk"/* 2>/dev/null | sort -V | tail -1 || true)"
  fi
fi
if [[ -z "${NDK}" || ! -d "${NDK}" ]]; then
  echo "build.sh: ANDROID_NDK_HOME not set and no NDK found — skip"
  exit 0
fi

ASSETS="${FFMPEG_ASSETS_DIR:-$ROOT/../../app/src/main/assets/ffmpeg}"
ABIS_CSV="${FFMPEG_ABIS:-arm64-v8a,armeabi-v7a,x86_64}"
IFS=',' read -r -a ABIS <<< "$ABIS_CSV"

SRC="$ROOT/src/ffmpeg-${FFMPEG_VERSION}"
BUILD_ROOT="$ROOT/build"
HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)"
if [[ ! -d "$NDK/toolchains/llvm/prebuilt/$HOST_TAG" ]]; then
  if [[ -d "$NDK/toolchains/llvm/prebuilt/linux-x86_64" ]]; then
    HOST_TAG="linux-x86_64"
  elif [[ -d "$NDK/toolchains/llvm/prebuilt/darwin-x86_64" ]]; then
    HOST_TAG="darwin-x86_64"
  elif [[ -d "$NDK/toolchains/llvm/prebuilt/darwin-arm64" ]]; then
    HOST_TAG="darwin-arm64"
  fi
fi
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
if [[ ! -d "$TOOLCHAIN" ]]; then
  echo "build.sh: toolchain not found at $TOOLCHAIN"
  exit 1
fi

export PATH="$TOOLCHAIN/bin:$PATH"

fetch_src() {
  if [[ -d "$SRC" ]]; then
    return 0
  fi
  mkdir -p "$ROOT/src"
  local tarball="$ROOT/src/ffmpeg-${FFMPEG_VERSION}.tar.xz"
  if [[ ! -f "$tarball" ]]; then
    echo "Downloading FFmpeg ${FFMPEG_VERSION}…"
    curl -L --fail -o "$tarball" \
      "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"
  fi
  tar -C "$ROOT/src" -xf "$tarball"
}

abi_to_target() {
  case "$1" in
    arm64-v8a) echo "aarch64-linux-android" ;;
    armeabi-v7a) echo "armv7a-linux-androideabi" ;;
    x86_64) echo "x86_64-linux-android" ;;
    x86) echo "i686-linux-android" ;;
    *) echo "unknown"; return 1 ;;
  esac
}

abi_to_arch() {
  case "$1" in
    arm64-v8a) echo "aarch64" ;;
    armeabi-v7a) echo "arm" ;;
    x86_64) echo "x86_64" ;;
    x86) echo "x86" ;;
  esac
}

abi_to_cpu() {
  case "$1" in
    arm64-v8a) echo "armv8-a" ;;
    armeabi-v7a) echo "armv7-a" ;;
    x86_64) echo "x86-64" ;;
    x86) echo "i686" ;;
  esac
}

build_abi() {
  local abi="$1"
  local target arch cpu
  target="$(abi_to_target "$abi")"
  arch="$(abi_to_arch "$abi")"
  cpu="$(abi_to_cpu "$abi")"
  local cc="${target}${FFMPEG_API_LEVEL}-clang"
  local out_dir="$ASSETS/$abi"
  local bin_out="$out_dir/ffmpeg"

  if [[ -x "$bin_out" ]]; then
    echo "[$abi] already present → $bin_out"
    return 0
  fi

  local bdir="$BUILD_ROOT/$abi"
  rm -rf "$bdir"
  mkdir -p "$bdir" "$out_dir"
  pushd "$bdir" >/dev/null

  # shellcheck disable=SC2086
  "$SRC/configure" \
    --prefix="$bdir/install" \
    --target-os=android \
    --arch="$arch" \
    --cpu="$cpu" \
    --enable-cross-compile \
    --cc="$cc" \
    --cxx="${target}${FFMPEG_API_LEVEL}-clang++" \
    --ld="$cc" \
    --ar=llvm-ar \
    --ranlib=llvm-ranlib \
    --strip=llvm-strip \
    --nm=llvm-nm \
    --extra-cflags="-O2 -fPIC" \
    --extra-ldflags="-static" \
    --pkg-config-flags=--static \
    --enable-static \
    --disable-shared \
    --disable-doc \
    --disable-programs \
    --enable-ffmpeg \
    --disable-ffplay \
    --disable-ffprobe \
    --disable-network \
    --disable-autodetect \
    $FFMPEG_ENABLE

  make -j"$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
  make install

  cp -f "$bdir/install/bin/ffmpeg" "$bin_out"
  chmod +x "$bin_out"
  llvm-strip "$bin_out" 2>/dev/null || true
  echo "[$abi] → $bin_out ($(du -h "$bin_out" | cut -f1))"
  popd >/dev/null
}

fetch_src
mkdir -p "$ASSETS" "$BUILD_ROOT"

for abi in "${ABIS[@]}"; do
  build_abi "$abi"
done

echo "FFmpeg build complete."
