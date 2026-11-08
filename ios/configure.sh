#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-only
# Every -D build-option override for the iOS configure lives here, because a set() in ios/CMakeLists.txt is read too late.
# Usage: ios/configure.sh <config> [extra cmake args...]   or   ios/configure.sh --help
#
# Configurations: ios-nollvm (arm64-ios, interpreter only) and ios-llvm (arm64-ios with the LLVM from ios/scripts/build-llvm-ios.sh).
# Environment: IOS_DEPLOYMENT_TARGET (default 17.4), BUILD_DIR (default ios/build/<config>), CMAKE (default cmake on PATH).
# With no argument or an unknown configuration it prints this header and exits non-zero.
set -euo pipefail

IOS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${IOS_DIR}/.." && pwd)"

# The one list of configuration names; the dispatch below is the only other place they appear.
CONFIGS=(ios-nollvm ios-llvm)

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

if [[ $# -lt 1 ]]; then
	usage >&2
	die "no configuration given. Valid configurations: ${CONFIGS[*]}"
fi

CONFIG="$1"
shift

if [[ "${CONFIG}" == "--help" || "${CONFIG}" == "-h" ]]; then
	usage
	exit 0
fi

CONFIG_KNOWN=0
for known in "${CONFIGS[@]}"; do
	if [[ "${CONFIG}" == "${known}" ]]; then
		CONFIG_KNOWN=1
	fi
done

if [[ "${CONFIG_KNOWN}" != "1" ]]; then
	usage >&2
	die "unknown configuration '${CONFIG}'. Valid configurations: ${CONFIGS[*]}"
fi

CMAKE="${CMAKE:-cmake}"
command -v "${CMAKE}" >/dev/null 2>&1 || die "${CMAKE} was not found on PATH."

BUILD_DIR="${BUILD_DIR:-${IOS_DIR}/build/${CONFIG}}"

# A flag name appears in exactly one array, which test_ios_configure_flags.py checks.

SHARED_FLAGS=(
	# Ninja, not Xcode: Xcode is multi-config, which forces USE_DISCORD_RPC off and skips the CMAKE_BUILD_TYPE default.
	-G Ninja
	-DCMAKE_BUILD_TYPE=Release
	# No Qt, no rpcs3qt, no desktop executable.
	-DBUILD_DESKTOP_FRONTEND=OFF
	# rpcs3_test links rpcs3_lib, which BUILD_DESKTOP_FRONTEND=OFF removes, so turning tests on fails the configure.
	-DBUILD_RPCS3_TESTS=OFF
	# -march=native means nothing when the target is not the host.
	-DUSE_NATIVE_INSTRUCTIONS=OFF
	-DUSE_PRECOMPILED_HEADERS=OFF
	# discordRPC is linked by rpcs3_lib, which is not built here.
	-DUSE_DISCORD_RPC=OFF
	# MoltenVK comes from ios/cmake/IosVulkan.cmake on iOS and Homebrew's molten-vk on macOS, not from the submodule's macOS-only build.
	-DUSE_SYSTEM_MVK=ON
	# ON on purpose, so find_package(OpenCV) fails and ps_move_tracker reports tracking unsupported.
	-DUSE_SYSTEM_OPENCV=ON
	# LTO over thousands of translation units costs RAM, disk and time while iterating.
	-DUSE_LTO=OFF
	# Dead-strip the executable link too, so the macOS leg exercises the same lever as the app.
	-DCMAKE_EXE_LINKER_FLAGS=-Wl,-dead_strip
)

NOLLVM_FLAGS=(
	# WITH_LLVM=OFF means the decoders must be set to the interpreters at run time, since the default is the LLVM recompiler.
	-DWITH_LLVM=OFF
)

LLVM_FLAGS=(
	-DWITH_LLVM=ON
	# LLVM is not built in-tree for iOS; ios/scripts/build-llvm-ios.sh builds it separately.
	-DBUILD_LLVM=OFF
	-DSTATIC_LINK_LLVM=ON
	# Pass the target list explicitly, or 3rdparty/llvm infers it from a compiler probe that can default to X86.
	-DLLVM_TARGETS_TO_BUILD=AArch64
)

IOS_FLAGS=(
	# CMake needs only CMAKE_SYSTEM_NAME for iOS, so there is no toolchain file to hold a second set of overrides.
	-DCMAKE_SYSTEM_NAME=iOS
	-DCMAKE_OSX_SYSROOT=iphoneos
	-DCMAKE_OSX_DEPLOYMENT_TARGET="${IOS_DEPLOYMENT_TARGET:-17.4}"
	# Also makes 3rdparty/DetectArchitecture.cmake return before any probe that cannot run.
	-DCMAKE_OSX_ARCHITECTURES=arm64
	# Keep try_compile building a static library, because linking an executable fails when cross-compiling to iOS.
	-DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY
	# A static-library try_compile never links, so link probes answer yes; seed the two OpenAL gets wrong, since Apple has no librt or libatomic.
	-DHAVE_LIBRT=
	-DHAVE_LIBATOMIC=
	# curl's accept4 probe answers yes the same way, but the iOS SDK does not declare accept4.
	-DHAVE_ACCEPT4=
	# Upstream defaults that are wrong for a cross-compile.
	-DUSE_SDL=OFF
	-DUSE_SYSTEM_SDL=OFF
	-DUSE_GAMEMODE=OFF
	# No system libraries in the iOS sysroot; a system zlib would resolve the host's into the link.
	-DUSE_SYSTEM_LIBUSB=OFF
	-DUSE_SYSTEM_CURL=OFF
	-DUSE_SYSTEM_ZLIB=OFF
	# ON so upstream's builtin FFmpeg branch, which has no iOS slice, is not taken; ios/cmake/IosFfmpeg.cmake supplies the tree.
	-DUSE_SYSTEM_FFMPEG=ON
	# OpenAL's RTKit probe falls through to the host's pkg-config D-Bus, and iOS has no bus.
	-DALSOFT_RTKIT=OFF
	# Desktop-only features.
	-DUSE_FAUDIO=OFF
	-DUSE_LIBEVDEV=OFF
	# cubeb's AudioUnit backend builds for iOS from upstream plus ios/core/cubeb-audiounit.patch, and ios/CMakeLists.txt drops the macOS-only run loop.
	-DUSE_AUDIOUNIT=ON
	# asmjit's shm_open dual mapping fails with EPERM under the iOS sandbox, so switch it off.
	-DASMJIT_NO_SHM_OPEN=ON
)

FLAGS=("${SHARED_FLAGS[@]}")

case "${CONFIG}" in
	ios-nollvm)
		FLAGS+=("${IOS_FLAGS[@]}" "${NOLLVM_FLAGS[@]}")
		;;
	ios-llvm)
		FLAGS+=("${IOS_FLAGS[@]}" "${LLVM_FLAGS[@]}")

		# LLVM_DIR is not passed here: ios/cmake/IosLlvm.cmake sets it and stops the configure when the install is missing.
		;;
esac

mkdir -p "${BUILD_DIR}"

say "config   : ${CONFIG}"
say "source   : ${REPO_DIR}"
say "build    : ${BUILD_DIR}"

exec "${CMAKE}" -S "${REPO_DIR}" -B "${BUILD_DIR}" "${FLAGS[@]}" "$@"
