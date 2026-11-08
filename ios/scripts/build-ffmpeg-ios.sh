#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-only
# Usage: ios/scripts/build-ffmpeg-ios.sh [--force | --help]
# Builds FFmpeg for arm64-ios from a SHA-256-pinned source release into ios/build-deps/ffmpeg-ios/: the five static archives rpcs3_emu links, plus headers.
# ios/cmake/IosFfmpeg.cmake points upstream's FindFFMPEG at that tree. FFmpeg has its own configure and 3rdparty/ffmpeg has no iOS slice, so this is a script, not CMake.
# The version is read from android/ffmpeg.cmake so the two cannot drift. The build is LGPL (--disable-gpl --disable-nonfree) and --disable-autodetect keeps host libraries out.
# Environment: IOS_DEPLOYMENT_TARGET (default 15.0), BUILD_DEPS_DIR (default ios/build-deps), FFMPEG_JOBS (default CPU count), CURL. --force rebuilds even when the stamp matches.
# A stamp of version, source hash, target, SDK and configure line lets a matching re-run exit at once.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${IOS_DIR}/.." && pwd)"

# SHA-256 of the pinned tarball, as observed after a good gpg check of its .asc; ffmpeg.org publishes no checksum file.
FFMPEG_SHA256="b6863adde98898f42602017462871b5f6333e65aec803fdd7a6308639c52edf3"

FFMPEG_VERSION_SOURCE="${REPO_DIR}/android/ffmpeg.cmake"
FFMPEG_BASE_URL="https://ffmpeg.org/releases"

# The five archives rpcs3_emu links; android/ffmpeg.cmake also imports avfilter and avdevice but links neither.
FFMPEG_LIBS=(libavformat.a libavcodec.a libavutil.a libswscale.a libswresample.a)

usage()
{
	# The header comment is the usage text, printed up to the first line that is not a comment.
	awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
}

die()
{
	echo "error: $*" >&2
	exit 1
}

say()
{
	echo "==> $*"
}

FORCE=0

while [[ $# -gt 0 ]]; do
	case "$1" in
		--help|-h)
			usage
			exit 0
			;;
		--force)
			FORCE=1
			shift
			;;
		*)
			usage >&2
			die "unknown argument '$1'."
			;;
	esac
done

CURL="${CURL:-curl}"

for tool in "${CURL}" shasum tar make xcrun; do
	command -v "${tool}" >/dev/null 2>&1 || die "${tool} was not found on PATH."
done

IOS_DEPLOYMENT_TARGET="${IOS_DEPLOYMENT_TARGET:-15.0}"

SDK_PATH="$(xcrun --sdk iphoneos --show-sdk-path)"
SDK_VERSION="$(xcrun --sdk iphoneos --show-sdk-version)"
[[ -d "${SDK_PATH}" ]] || die "the iphoneos SDK path does not exist: ${SDK_PATH}"

CC_BIN="$(xcrun --sdk iphoneos -f clang)"
AR_BIN="$(xcrun --sdk iphoneos -f ar)"
RANLIB_BIN="$(xcrun --sdk iphoneos -f ranlib)"
STRIP_BIN="$(xcrun --sdk iphoneos -f strip)"
NM_BIN="$(xcrun --sdk iphoneos -f nm)"

if [[ -z "${FFMPEG_JOBS:-}" ]]; then
	FFMPEG_JOBS="$(sysctl -n hw.logicalcpu 2>/dev/null || echo 4)"
fi

# Read the version from android/ffmpeg.cmake so the two cannot drift.

[[ -f "${FFMPEG_VERSION_SOURCE}" ]] || die \
	"${FFMPEG_VERSION_SOURCE} is missing, so the version this build must match cannot be read. The iOS FFmpeg is pinned to Android's version; restating it here instead would be exactly the drift the read prevents."

FFMPEG_VERSION="$(
	sed -n 's/^[[:space:]]*set(FFMPEG_VERSION[[:space:]]\{1,\}\([0-9][0-9.]*\)).*$/\1/p' \
		"${FFMPEG_VERSION_SOURCE}" | head -1
)"

[[ -n "${FFMPEG_VERSION}" ]] || die \
	"no set(FFMPEG_VERSION <n>) line found in ${FFMPEG_VERSION_SOURCE}. Refusing to guess a version: a silently defaulted FFmpeg is a silently different emulator."

BUILD_DEPS_DIR="${BUILD_DEPS_DIR:-${IOS_DIR}/build-deps}"
PREFIX="${BUILD_DEPS_DIR}/ffmpeg-ios"
SRC_ROOT="${BUILD_DEPS_DIR}/ffmpeg-src"
TARBALL_NAME="ffmpeg-${FFMPEG_VERSION}.tar.xz"
TARBALL="${SRC_ROOT}/${TARBALL_NAME}"
SRC_DIR="${SRC_ROOT}/ffmpeg-${FFMPEG_VERSION}"
BUILD_DIR="${SRC_ROOT}/build-arm64-ios"
STAMP="${PREFIX}/.armsx3-ffmpeg-stamp"

CFLAGS_IOS="-arch arm64 -isysroot ${SDK_PATH} -mios-version-min=${IOS_DEPLOYMENT_TARGET} -fno-common"
LDFLAGS_IOS="-arch arm64 -isysroot ${SDK_PATH} -mios-version-min=${IOS_DEPLOYMENT_TARGET}"

CONFIGURE_ARGS=(
	"--prefix=${PREFIX}"
	# --target-os is darwin, not ios: FFmpeg has no ios target-os, and the sysroot and minimum-version flags make it iOS.
	--enable-cross-compile
	--target-os=darwin
	--arch=arm64
	"--sysroot=${SDK_PATH}"
	"--cc=${CC_BIN}"
	"--ar=${AR_BIN}"
	"--ranlib=${RANLIB_BIN}"
	"--strip=${STRIP_BIN}"
	"--nm=${NM_BIN}"
	"--extra-cflags=${CFLAGS_IOS}"
	"--extra-ldflags=${LDFLAGS_IOS}"
	# Static only: the app is one signed binary.
	--enable-static
	--disable-shared
	--enable-pic
	# Mandatory: configure would build the command-line tools for the target, which the host cannot run and signing has no target for.
	--disable-programs
	--disable-doc
	# No capture devices or sockets on the media path.
	--disable-avdevice
	--disable-network
	# Nothing in the tree references avfilter, yet the collector would sweep it in; the codec tables stay whole, because dropping a decoder breaks whichever titles use it.
	--disable-avfilter
	# Keeps a host macOS zlib, lzma, bzip2 or iconv out of an arm64-ios archive.
	--disable-autodetect
	# Apple hardware codecs stay off until someone measures them against RSX frame pacing.
	--disable-videotoolbox
	--disable-audiotoolbox
	--disable-debug
	# FFmpeg's defaults, spelled out so a later --enable-gpl cannot slip in; test_ffmpeg_ios_pin.py asserts both.
	--disable-gpl
	--disable-nonfree
)

CONFIGURE_LINE="./configure ${CONFIGURE_ARGS[*]}"

stamp_body()
{
	printf 'version=%s\n' "${FFMPEG_VERSION}"
	printf 'source_sha256=%s\n' "${FFMPEG_SHA256}"
	printf 'ios_deployment_target=%s\n' "${IOS_DEPLOYMENT_TARGET}"
	printf 'iphoneos_sdk=%s\n' "${SDK_VERSION}"
	printf 'configure=%s\n' "${CONFIGURE_LINE}"
}

archives_present()
{
	local lib
	for lib in "${FFMPEG_LIBS[@]}"; do
		[[ -f "${PREFIX}/lib/${lib}" ]] || return 1
	done
	[[ -f "${PREFIX}/include/libavcodec/avcodec.h" ]] || return 1
	return 0
}

if [[ "${FORCE}" != "1" && -f "${STAMP}" ]] && archives_present; then
	if [[ "$(stamp_body)" == "$(cat "${STAMP}")" ]]; then
		say "ffmpeg: already built (${FFMPEG_VERSION}, ios ${IOS_DEPLOYMENT_TARGET}) — nothing to do."
		say "        prefix: ${PREFIX}"
		exit 0
	fi
	say "ffmpeg: stamp differs from the requested build — rebuilding."
fi

mkdir -p "${SRC_ROOT}"

if [[ ! -f "${TARBALL}" ]]; then
	say "ffmpeg: downloading ${TARBALL_NAME}"
	"${CURL}" -fsSL --retry 3 -o "${TARBALL}" "${FFMPEG_BASE_URL}/${TARBALL_NAME}"
fi

GOT_SHA="$(shasum -a 256 "${TARBALL}" | awk '{print $1}')"

if [[ "${GOT_SHA}" != "${FFMPEG_SHA256}" ]]; then
	echo "error: ${TARBALL_NAME} checksum mismatch." >&2
	echo "  expected ${FFMPEG_SHA256}" >&2
	echo "  got      ${GOT_SHA}" >&2
	echo "The artifact has been DELETED. Re-run to fetch it again, or correct the pin in" >&2
	echo "this script if the release was legitimately re-cut." >&2
	echo "Do NOT proceed to build a shipped library from source of unknown identity." >&2
	rm -f "${TARBALL}"
	exit 1
fi

say "ffmpeg: ${TARBALL_NAME} sha256 OK"

# Extract into a scratch directory and move the result into place, so a partial extraction is never mistaken for a source tree.
if [[ ! -d "${SRC_DIR}" ]]; then
	STAGE="${SRC_ROOT}/.stage"
	rm -rf "${STAGE}"
	mkdir -p "${STAGE}"
	say "ffmpeg: extracting ${TARBALL_NAME}"
	tar xf "${TARBALL}" -C "${STAGE}"
	[[ -d "${STAGE}/ffmpeg-${FFMPEG_VERSION}" ]] || die \
		"${TARBALL_NAME} did not unpack to ffmpeg-${FFMPEG_VERSION}/ as expected."
	mv "${STAGE}/ffmpeg-${FFMPEG_VERSION}" "${SRC_DIR}"
	rm -rf "${STAGE}"
fi

# The unpacked VERSION file is a second check that the archive is the release android/ffmpeg.cmake names, which the SHA-256 would miss behind a hand-edited URL.
if [[ -f "${SRC_DIR}/VERSION" ]]; then
	UNPACKED_VERSION="$(tr -d '[:space:]' < "${SRC_DIR}/VERSION")"
	[[ "${UNPACKED_VERSION}" == "${FFMPEG_VERSION}" ]] || die \
		"the unpacked source says it is FFmpeg ${UNPACKED_VERSION}, but android/ffmpeg.cmake pins ${FFMPEG_VERSION}."
fi

# Out-of-tree and wiped on every real build, because a stale tree from another SDK or target gives archives that do not match the stamp.
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"

# A previous install must not survive, or leftover archives would satisfy the presence checks.
rm -rf "${PREFIX}"

say "ffmpeg: configuring ${FFMPEG_VERSION} for arm64-ios (min ${IOS_DEPLOYMENT_TARGET}, SDK ${SDK_VERSION})"
(
	cd "${BUILD_DIR}"
	"${SRC_DIR}/configure" "${CONFIGURE_ARGS[@]}"
)

say "ffmpeg: building with -j${FFMPEG_JOBS}"
make -C "${BUILD_DIR}" -j"${FFMPEG_JOBS}"
make -C "${BUILD_DIR}" install

for lib in "${FFMPEG_LIBS[@]}"; do
	path="${PREFIX}/lib/${lib}"
	[[ -f "${path}" ]] || die "expected archive missing after install: ${path}"

	if ! lipo -info "${path}" 2>/dev/null | grep -q 'arm64'; then
		die "${lib} is not arm64: $(lipo -info "${path}" 2>&1)"
	fi

	# vtool needs a Mach-O, so probe one extracted member; lipo alone would pass a macOS arm64 archive.
	member="$(ar -t "${path}" | grep -m1 '\.o$' || true)"
	[[ -n "${member}" ]] || die "${lib} contains no object member to probe: $(ar -t "${path}" | head -5)"

	probe="${BUILD_DIR}/.platform-probe.o"
	ar p "${path}" "${member}" > "${probe}"

	if ! vtool -show "${probe}" 2>/dev/null | grep -qi 'platform IOS'; then
		die "${lib} (member ${member}) does not carry an iOS platform load command. vtool says: $(vtool -show "${probe}" 2>&1 | head -20)"
	fi
	rm -f "${probe}"
done

[[ -f "${PREFIX}/include/libavcodec/avcodec.h" ]] || die \
	"${PREFIX}/include/libavcodec/avcodec.h is missing; ios/cmake/IosFfmpeg.cmake primes FFMPEG_INCLUDE_DIR at ${PREFIX}/include and upstream includes <libavcodec/avcodec.h> through it."

# A program under the prefix means --disable-programs was lost; those are target binaries the host cannot run.
for unwanted in ffmpeg ffprobe ffplay; do
	[[ ! -e "${PREFIX}/bin/${unwanted}" ]] || die \
		"${PREFIX}/bin/${unwanted} exists, so --disable-programs was not in effect. That is a target executable the host cannot run and the signing step has no target for."
done

stamp_body > "${STAMP}"

say "ffmpeg: ready — ${FFMPEG_VERSION} arm64-ios"
say "  prefix : ${PREFIX}"
say "  lib    : ${PREFIX}/lib"
say "  include: ${PREFIX}/include"
say "  stamp  : ${STAMP}"
