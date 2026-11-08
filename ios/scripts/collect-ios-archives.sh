#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-only
# Verifies every arm64-ios static archive a configuration produced and merges them into the one archive ios/project.yml links.
# Usage: ios/scripts/collect-ios-archives.sh <config>   or   --help   (ios-nollvm or ios-llvm; build first, this only collects)
# It enumerates what was produced, so there is no hand-kept list of hundreds of archives to drift.
# Every archive must be arm64 and carry the iOS platform load command, since lipo alone cannot tell macOS arm64 from iOS arm64.
# Environment: BUILD_ROOT (default ios/build/<config>), OUTPUT (default <BUILD_ROOT>/libarmsx3_ios_core.a), MANIFEST (default <BUILD_ROOT>/armsx3-ios-link-manifest.txt).
set -euo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_DIR="$(cd "${SCRIPTS_DIR}/.." && pwd)"
REPO_DIR="$(cd "${IOS_DIR}/.." && pwd)"

usage()
{
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
	die "no configuration given."
fi

CONFIG="$1"
shift || true

if [[ "${CONFIG}" == "--help" || "${CONFIG}" == "-h" ]]; then
	usage
	exit 0
fi

case "${CONFIG}" in
	ios-nollvm | ios-llvm) ;;
	*)
		usage >&2
		die "unknown configuration '${CONFIG}'. This script collects the two iOS configurations only."
		;;
esac

BUILD_ROOT="${BUILD_ROOT:-${IOS_DIR}/build/${CONFIG}}"
OUTPUT="${OUTPUT:-${BUILD_ROOT}/libarmsx3_ios_core.a}"
MANIFEST="${MANIFEST:-${BUILD_ROOT}/armsx3-ios-link-manifest.txt}"

[[ -d "${BUILD_ROOT}" ]] || die "no build tree at ${BUILD_ROOT}. Run ios/configure.sh ${CONFIG} and build rpcs3_emu first."

command -v libtool >/dev/null 2>&1 || die "libtool was not found on PATH. It ships with the Xcode command line tools."
command -v otool >/dev/null 2>&1 || die "otool was not found on PATH. It ships with the Xcode command line tools."
command -v lipo >/dev/null 2>&1 || die "lipo was not found on PATH. It ships with the Xcode command line tools."

EMU_ARCHIVE="${BUILD_ROOT}/rpcs3/Emu/librpcs3_emu.a"
[[ -f "${EMU_ARCHIVE}" ]] || die "librpcs3_emu.a is absent from ${BUILD_ROOT}. Build the rpcs3_emu target before collecting."

# The cross-built FFmpeg and LLVM archives are link inputs too, since the primed cache variables point at them.

ARCHIVES=()

while IFS= read -r found; do
	# Skip the merged output itself, or a re-run would swallow its previous output and double every symbol.
	[[ "${found}" == "${OUTPUT}" ]] && continue
	ARCHIVES+=("${found}")
done < <(find "${BUILD_ROOT}" -name '*.a' -type f | sort)

FFMPEG_LIB_DIR="${IOS_DIR}/build-deps/ffmpeg-ios/lib"
if [[ -d "${FFMPEG_LIB_DIR}" ]]; then
	while IFS= read -r found; do
		ARCHIVES+=("${found}")
	done < <(find "${FFMPEG_LIB_DIR}" -name '*.a' -type f | sort)
else
	die "the arm64-ios FFmpeg tree is absent at ${FFMPEG_LIB_DIR}. Run ios/scripts/build-ffmpeg-ios.sh."
fi

if [[ "${CONFIG}" == "ios-llvm" ]]; then
	LLVM_LIB_DIR="${IOS_DIR}/build-deps/llvm-ios-install/lib"
	if [[ -d "${LLVM_LIB_DIR}" ]]; then
		while IFS= read -r found; do
			ARCHIVES+=("${found}")
		done < <(find "${LLVM_LIB_DIR}" -name '*.a' -type f | sort)
	else
		die "the arm64-ios LLVM install is absent at ${LLVM_LIB_DIR}. Run ios/scripts/build-llvm-ios.sh."
	fi
fi

[[ ${#ARCHIVES[@]} -gt 0 ]] || die "no archives found for ${CONFIG}."

# Check architecture and platform on every archive; LC_BUILD_VERSION platform 1 is macOS, 2 iOS, 3 tvOS, 4 watchOS, 6 Mac Catalyst, 7 iOS simulator, and only 2 passes.

say "config   : ${CONFIG}"
say "build    : ${BUILD_ROOT}"
say "archives : ${#ARCHIVES[@]}"

bad_arch=()
bad_platform=()
lowest_minos=""

for archive in "${ARCHIVES[@]}"; do
	rel="${archive#"${REPO_DIR}"/}"

	arches="$(lipo -archs "${archive}" 2>/dev/null || true)"
	if [[ "${arches}" != "arm64" ]]; then
		bad_arch+=("${rel} reports '${arches:-<unreadable>}'")
	fi

	platforms="$(otool -l "${archive}" 2>/dev/null | awk '$1 == "platform" { print $2 }' | sort -u | tr '\n' ' ')"
	platforms="${platforms% }"
	if [[ "${platforms}" != "2" ]]; then
		bad_platform+=("${rel} reports platform '${platforms:-<none>}'")
	fi

	# Report the lowest minos across inputs, because the separately cross-built archives can lag the deployment target.
	minos="$(otool -l "${archive}" 2>/dev/null | awk '$1 == "minos" { print $2 }' | sort -uV | head -1)"
	if [[ -n "${minos}" ]]; then
		if [[ -z "${lowest_minos}" ]]; then
			lowest_minos="${minos}"
		else
			lowest_minos="$(printf '%s\n%s\n' "${lowest_minos}" "${minos}" | sort -V | head -1)"
		fi
	fi
done

if [[ ${#bad_arch[@]} -gt 0 ]]; then
	echo "error: ${#bad_arch[@]} archive(s) are not arm64-only:" >&2
	printf '  %s\n' "${bad_arch[@]}" >&2
	exit 1
fi

if [[ ${#bad_platform[@]} -gt 0 ]]; then
	echo "error: ${#bad_platform[@]} archive(s) do not report the iOS platform (LC_BUILD_VERSION platform 2)." >&2
	echo "A macOS archive has the same ARCHITECTURE as an iOS one, so this is the check that catches" >&2
	echo "a host library resolving into an arm64-ios link:" >&2
	printf '  %s\n' "${bad_platform[@]}" >&2
	exit 1
fi

say "verified : every archive is arm64 and reports the iOS platform"
say "minos    : lowest across inputs is ${lowest_minos:-<none reported>}"

mkdir -p "$(dirname "${OUTPUT}")"

: >"${MANIFEST}"
for archive in "${ARCHIVES[@]}"; do
	echo "${archive#"${REPO_DIR}"/}" >>"${MANIFEST}"
done

# libtool warns about duplicate member basenames, which is harmless, so filter it to keep real diagnostics visible.
rm -f "${OUTPUT}"
libtool -static -o "${OUTPUT}" "${ARCHIVES[@]}" 2> >(grep -v "has the same member name" >&2 || true)

[[ -f "${OUTPUT}" ]] || die "libtool reported success but ${OUTPUT} was not written."

say "merged   : ${OUTPUT#"${REPO_DIR}"/} ($(du -h "${OUTPUT}" | cut -f1))"
say "manifest : ${MANIFEST#"${REPO_DIR}"/} (${#ARCHIVES[@]} entries)"
