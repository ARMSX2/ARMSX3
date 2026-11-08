#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-only
# Builds librashader's runtime as an iOS dylib: bash ios/scripts/build-librashader-ios.sh
# It is the librashader-capi cdylib at one pinned commit for aarch64-apple-ios, Vulkan runtime only, into ios/build-deps/librashader-ios/.
# The same checkout's include/ gives rpcs3_emu its loader header, so the header and the dylib come from one commit.
# The dylib is dlopened and never linked, because librashader is MPL-2.0 OR GPL-3.0-only beside a GPL-2.0-only app; only the cdylib is copied.
# The clone and cargo's crate fetch are the only network use, and crates come from the pinned Cargo.lock (--locked).
# A re-run checks the clone is at the pin and clean, then rebuilds; an interrupted tree is refused by name, so delete that folder.
set -euo pipefail

PIN=87e8a97b50516d997defeaa168173dcd185d4022
REPO_URL=https://github.com/SnowflakePowered/librashader.git

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="${ROOT_DIR}/ios/build-deps/librashader-src"
TARGET_DIR="${ROOT_DIR}/ios/build-deps/librashader-target"
OUT="${ROOT_DIR}/ios/build-deps/librashader-ios"
DYLIB="${OUT}/librashader.dylib"

# Equal to IOS_DEPLOYMENT_TARGET in configure.sh; unset, rustc links for iOS 10 and the dylib dies at load on ___chkstk_darwin.
export IPHONEOS_DEPLOYMENT_TARGET=17.4

fail() {
	echo "build-librashader-ios: FAIL $1" >&2
	exit 1
}

if [[ ! -d "${SRC}" ]]; then
	git clone --no-checkout "${REPO_URL}" "${SRC}" || fail "clone: delete ios/build-deps/librashader-src and run again"
	git -C "${SRC}" checkout --detach "${PIN}" || fail "checkout: delete ios/build-deps/librashader-src and run again"
fi

HEAD="$(git -C "${SRC}" rev-parse HEAD 2>/dev/null || true)"
[[ "${HEAD}" == "${PIN}" ]] || fail "pin: ios/build-deps/librashader-src is at '${HEAD}', not ${PIN}; delete that folder and run again"
[[ -z "$(git -C "${SRC}" status --porcelain)" ]] || fail "dirty: ios/build-deps/librashader-src has local changes; delete that folder and run again"

cargo build --release --locked \
	--manifest-path "${SRC}/librashader-capi/Cargo.toml" \
	--target aarch64-apple-ios \
	--target-dir "${TARGET_DIR}" \
	--no-default-features --features runtime-vulkan \
	|| fail "cargo: runtime-vulkan did not build at ${PIN}"

BUILT="${TARGET_DIR}/aarch64-apple-ios/release/liblibrashader_capi.dylib"
[[ -s "${BUILT}" ]] || fail "output: ${BUILT#"${ROOT_DIR}/"} was not produced"

mkdir -p "${OUT}"
cp "${BUILT}" "${DYLIB}"
install_name_tool -id @rpath/librashader.dylib "${DYLIB}"

BUILD_INFO="$(vtool -show-build "${DYLIB}")"
grep -q 'platform IOS$' <<<"${BUILD_INFO}" || fail "check: the dylib's platform is not IOS"
grep -q 'minos 17.4$' <<<"${BUILD_INFO}" || fail "check: the dylib's minos is not 17.4"
nm -gU "${DYLIB}" | grep -q ' _libra_vk_filter_chain_create$' || fail "check: _libra_vk_filter_chain_create is not exported"
FOREIGN="$(otool -L "${DYLIB}" | tail -n +2 | grep -vE '^[[:space:]]+(/usr/lib/|/System/|@rpath/librashader\.dylib )' || true)"
[[ -z "${FOREIGN}" ]] || fail "check: the dylib depends on something outside /usr/lib and /System: ${FOREIGN}"

echo "LIBRASHADER-IOS-OK $(shasum -a 256 "${DYLIB}" | cut -d' ' -f1)"
