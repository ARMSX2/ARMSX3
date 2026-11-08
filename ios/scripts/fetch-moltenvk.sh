#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-only
# Fetches the MoltenVK release named by MOLTENVK_TAG and checks its SHA-256 before extracting it into the gitignored ios/3rdparty/MoltenVK.
# It downloads because a source build clones and builds six dependencies; upstream's 3rdparty/MoltenVK builds only the macOS scheme and stays untouched.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

MOLTENVK_TAG="${MOLTENVK_TAG:-v1.4.2}"
DEST_DIR="${DEST_DIR:-${ROOT_DIR}/3rdparty/MoltenVK}"
BASE_URL="https://github.com/KhronosGroup/MoltenVK/releases/download/${MOLTENVK_TAG}"

# SHA-256 of the two published artifacts for this tag; a mismatch means the release was re-cut or the download was tampered with.
SHA_IOS="b5d947b1660e6e9fed40b9cd2387e160aaab9e80b775c0cef7e14059405178c1"
SHA_MACOS="f95765a6229cb7b915990a2890ce12ebe36a730b021545d3d52ae69ce4c4024e"

IOS_LIB="${DEST_DIR}/ios-pkg/MoltenVK/static/MoltenVK.xcframework/ios-arm64/libMoltenVK.a"
MACOS_DYLIB="${DEST_DIR}/macos-pkg/MoltenVK/dylib/macOS/libMoltenVK.dylib"
INCLUDE_DIR="${DEST_DIR}/ios-pkg/MoltenVK/include"

# The stamp records the extracted tag, so a re-pin clears the stale artifacts instead of keeping them under the new tag.
STAMP="${DEST_DIR}/.moltenvk-tag"

if [[ -f "${IOS_LIB}" && -f "${MACOS_DYLIB}" && -d "${INCLUDE_DIR}/vulkan" \
	&& -f "${STAMP}" && "$(cat "${STAMP}")" == "${MOLTENVK_TAG}" ]]; then
	echo "moltenvk: already present (${MOLTENVK_TAG}) — nothing to fetch."
	exit 0
fi

# Remove each stale child by name, never DEST_DIR itself or a glob, so a wrong DEST_DIR override cannot take a tree with it.
if [[ -e "${DEST_DIR}/ios-pkg" || -e "${DEST_DIR}/macos-pkg" ]]; then
	echo "moltenvk: clearing ${DEST_DIR} artifacts (does not hold ${MOLTENVK_TAG})"
fi
for stale in ios-pkg macos-pkg .stage MoltenVK-ios.tar MoltenVK-macos.tar .moltenvk-tag; do
	rm -rf "${DEST_DIR:?}/${stale}"
done

for tool in curl shasum tar; do
	if ! command -v "${tool}" >/dev/null 2>&1; then
		echo "error: ${tool} was not found." >&2
		exit 1
	fi
done

mkdir -p "${DEST_DIR}"

fetch_and_verify() {
	local name="$1" want="$2" path="${DEST_DIR}/$1"

	if [[ ! -f "${path}" ]]; then
		echo "moltenvk: downloading ${name} (${MOLTENVK_TAG})"
		curl -fsSL --retry 3 -o "${path}" "${BASE_URL}/${name}"
	fi

	local got
	got="$(shasum -a 256 "${path}" | awk '{print $1}')"

	if [[ "${got}" != "${want}" ]]; then
		echo "error: ${name} checksum mismatch." >&2
		echo "  expected ${want}" >&2
		echo "  got      ${got}" >&2
		echo "Delete ${path} and re-run, or correct the pin in this script if the" >&2
		echo "release was legitimately re-cut. Do NOT proceed to a measurement against" >&2
		echo "an artifact whose identity is unknown." >&2
		rm -f "${path}"
		exit 1
	fi

	echo "moltenvk: ${name} sha256 OK"
}

extract() {
	local tarball="$1" dest="$2"

	if [[ -d "${DEST_DIR}/${dest}" ]]; then
		return 0
	fi

	# Both archives unpack to MoltenVK/, so rename them on extraction.
	rm -rf "${DEST_DIR}/.stage"
	mkdir -p "${DEST_DIR}/.stage"
	# -m stamps files with the current time, or ninja would never recompile against a re-pin's older headers.
	tar xmf "${DEST_DIR}/${tarball}" -C "${DEST_DIR}/.stage"
	mv "${DEST_DIR}/.stage/MoltenVK" "${DEST_DIR}/${dest}"
	rmdir "${DEST_DIR}/.stage"
	echo "moltenvk: extracted ${tarball} -> ios/3rdparty/MoltenVK/${dest}"
}

fetch_and_verify "MoltenVK-ios.tar" "${SHA_IOS}"
fetch_and_verify "MoltenVK-macos.tar" "${SHA_MACOS}"

extract "MoltenVK-ios.tar" "ios-pkg"
extract "MoltenVK-macos.tar" "macos-pkg"

for required in "${IOS_LIB}" "${MACOS_DYLIB}" "${INCLUDE_DIR}/vulkan/vulkan.h"; do
	if [[ ! -e "${required}" ]]; then
		echo "error: expected artifact missing after extraction: ${required}" >&2
		exit 1
	fi
done

printf '%s\n' "${MOLTENVK_TAG}" > "${STAMP}"

echo "moltenvk: ready (${MOLTENVK_TAG})"
echo "  ios static : ios/3rdparty/MoltenVK/ios-pkg/MoltenVK/static/MoltenVK.xcframework"
echo "  macos dylib: ios/3rdparty/MoltenVK/macos-pkg/MoltenVK/dylib/macOS/libMoltenVK.dylib"
echo "  headers    : ios/3rdparty/MoltenVK/ios-pkg/MoltenVK/include"
