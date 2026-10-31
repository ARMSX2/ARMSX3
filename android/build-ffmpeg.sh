#!/usr/bin/env bash
# ARMSX3: builds the ffmpeg the Android core links, the same version and components upstream ships.
#
# Upstream RPCS3 builds ffmpeg 8.1.1 through vcpkg (3rdparty/ffmpeg, RPCS3/ffmpeg-core) with only
# what RPCS3 uses enabled and its assembly on, but publishes no Android build. Android used to
# download RPCS3-Android's 5.1 prebuilt instead: every component ffmpeg has, built with
# --disable-asm, so every movie and XMB icon decoded without NEON, and upstream code written for
# ffmpeg 7.1+ needed a shim (util/ffmpeg_compat.h) or silently took an older path (cellVdec's
# per-picture userdata).
#
# The component list below is upstream's, from 3rdparty/ffmpeg/ffmpeg.patch. Keep the two in step
# when upstream bumps ffmpeg-core: the version and SHA512 are the ones vcpkg's ffmpeg port pins at
# the vcpkg tag that ffmpeg-core's build.yml checks out.
#
# Usage: android/build-ffmpeg.sh <ndk dir> <install prefix>
# Environment: FFMPEG_API (default 29, the lowest any variant runs on), JOBS.

set -euo pipefail

VERSION=8.1.1
SHA512=e858e92e5eb08d562302cde371af55917df6e1fe53994e18462a3c929a40ede1828c2bd53c2a7d65a2cfd791782ead3cd94efb2def904f49cb5dd8ab5cd4256f
URL="https://github.com/ffmpeg/ffmpeg/archive/n${VERSION}.tar.gz"

NDK="${1:?usage: build-ffmpeg.sh <ndk dir> <install prefix>}"
PREFIX="${2:?usage: build-ffmpeg.sh <ndk dir> <install prefix>}"
API="${FFMPEG_API:-29}"
JOBS="${JOBS:-$(sysctl -n hw.ncpu 2>/dev/null || nproc)}"

case "$(uname -s)" in
	Darwin) HOST_TAG=darwin-x86_64 ;;
	Linux) HOST_TAG=linux-x86_64 ;;
	*) echo "build-ffmpeg.sh: unsupported host $(uname -s)" >&2; exit 1 ;;
esac
TC="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
CC="$TC/bin/aarch64-linux-android${API}-clang"
CXX="$TC/bin/aarch64-linux-android${API}-clang++"
[ -x "$CC" ] || { echo "build-ffmpeg.sh: no compiler at $CC" >&2; exit 1; }

WORK="${PREFIX}.work"
rm -rf "$WORK"
mkdir -p "$WORK"

TARBALL="$WORK/ffmpeg-n${VERSION}.tar.gz"
echo "build-ffmpeg.sh: downloading ffmpeg ${VERSION}"
curl -fL --retry 3 -o "$TARBALL" "$URL"
GOT=$(shasum -a 512 "$TARBALL" | cut -d' ' -f1)
if [ "$GOT" != "$SHA512" ]; then
	echo "build-ffmpeg.sh: SHA512 mismatch for $URL" >&2
	echo "  expected $SHA512" >&2
	echo "  got      $GOT" >&2
	exit 1
fi

mkdir -p "$WORK/src"
tar -xzf "$TARBALL" -C "$WORK/src" --strip-components=1

# Upstream's options (vcpkg defaults plus ffmpeg.patch). Names that match nothing in ffmpeg
# (the mov decoder, the h264 and mp3 encoders, which need external libraries) only warn, as
# they do upstream. avdevice and avfilter are left out: nothing on Android links them.
COMPONENTS=(
	--disable-everything --disable-network
	--enable-decoder=aac --enable-decoder=aac_latm --enable-decoder=atrac3 --enable-decoder=atrac3p
	--enable-decoder=atrac9 --enable-decoder=mp3 --enable-decoder=pcm_s16le --enable-decoder=pcm_s8
	--enable-decoder=mov --enable-decoder=h264 --enable-decoder=mpeg4 --enable-decoder=mpeg2video
	--enable-decoder=mjpeg --enable-decoder=mjpegb
	--enable-encoder=pcm_s16le --enable-encoder=mp3 --enable-encoder=ac3 --enable-encoder=aac
	--enable-encoder=ffv1 --enable-encoder=mpeg4 --enable-encoder=mjpeg --enable-encoder=h264
	--enable-muxer=avi --enable-muxer=h264 --enable-muxer=mjpeg --enable-muxer=mp4
	--enable-demuxer=h264 --enable-demuxer=m4v --enable-demuxer=mp3 --enable-demuxer=mpegvideo
	--enable-demuxer=mpegps --enable-demuxer=mjpeg --enable-demuxer=mov --enable-demuxer=avi
	--enable-demuxer=aac --enable-demuxer=pmp --enable-demuxer=oma --enable-demuxer=pcm_s16le
	--enable-demuxer=pcm_s8 --enable-demuxer=wav
	--enable-parser=h264 --enable-parser=mpeg4video --enable-parser=mpegaudio --enable-parser=mpegvideo
	--enable-parser=mjpeg --enable-parser=aac --enable-parser=aac_latm
	--enable-protocol=file
	--enable-bsf=mjpeg2jpeg
)

# -fvisibility=hidden: these static libraries end up inside the core's shared library, where a
# default-visibility symbol can be interposed, and lld refuses the PC-relative addressing ffmpeg's
# NEON code uses on its own tables (ff_tx_tab_*) for any symbol that could be. ffmpeg's own
# shared libraries hide them with a version script; upstream links into an executable, where the
# question never comes up.
cd "$WORK/src"
./configure \
	--prefix="$PREFIX" \
	--target-os=android --arch=aarch64 --enable-cross-compile \
	--cc="$CC" --cxx="$CXX" \
	--ar="$TC/bin/llvm-ar" --nm="$TC/bin/llvm-nm" --ranlib="$TC/bin/llvm-ranlib" --strip="$TC/bin/llvm-strip" \
	--pkg-config=false \
	--enable-static --disable-shared --enable-pic \
	--disable-programs --disable-doc --disable-debug \
	--disable-avdevice --disable-avfilter \
	--enable-runtime-cpudetect --disable-autodetect \
	--extra-cflags="-fvisibility=hidden -ffunction-sections -fdata-sections" \
	"${COMPONENTS[@]}"

make -j"$JOBS"
rm -rf "$PREFIX"
make install

# What was built, for anyone wondering later which ffmpeg an APK carries.
{
	echo "ffmpeg ${VERSION} (${URL}, sha512 ${SHA512})"
	echo "api ${API}, ndk $(basename "$NDK")"
	grep -m1 FFMPEG_CONFIGURATION config.h
} > "$PREFIX/BUILDINFO"

cd /
rm -rf "$WORK"
echo "build-ffmpeg.sh: installed ffmpeg ${VERSION} to $PREFIX"
