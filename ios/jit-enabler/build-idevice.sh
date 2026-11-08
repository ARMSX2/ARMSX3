#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-only
# Builds idevice-ffi (MIT) for aarch64-apple-ios at the audited pin, --locked and without aws-lc-rs or ring, into the gitignored ios/3rdparty/jit-enabler/.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT}"

PIN="d32c8189c51c2789496b0768039419c3705498c3"
TAG="v0.1.68"
REPO_URL="https://github.com/jkcoxson/idevice.git"
TREE="ios/3rdparty/jit-enabler"
SRC="${TREE}/idevice"
OUT="${TREE}/out"
TARGET="aarch64-apple-ios"
FEATURES="debug_proxy dvt rsd remote_pairing tunnel_tcp_stack rustcrypto mobile_image_mounter tss cryptexd"

export IPHONEOS_DEPLOYMENT_TARGET="17.4"
export CARGO_TARGET_DIR="${ROOT}/${TREE}/cargo-target"

for tool in git cargo rustup; do
	if ! command -v "${tool}" >/dev/null 2>&1; then
		echo "build-idevice.sh: ${tool} is not on PATH." >&2
		exit 2
	fi
done

installed="$(rustup target list --installed)"
if ! grep -qx "${TARGET}" <<< "${installed}"; then
	echo "build-idevice.sh: the ${TARGET} Rust target is not installed; install it yourself." >&2
	exit 2
fi

if ! git check-ignore -q "${TREE}/probe"; then
	echo "build-idevice.sh: refusing: ${TREE}/ is not gitignored, so a build product could be staged." >&2
	exit 2
fi

if [[ -e "${SRC}" ]]; then
	have="$(git -C "${SRC}" rev-parse HEAD 2>/dev/null || echo none)"
	if [[ "${have}" != "${PIN}" ]]; then
		echo "build-idevice.sh: ${SRC} exists at ${have}, not the pin ${PIN}; refusing to reuse or overwrite it." >&2
		exit 1
	fi
	echo "build-idevice.sh: reusing ${SRC} at ${PIN}"
else
	mkdir -p "${TREE}"
	git clone --depth 1 --branch "${TAG}" --recurse-submodules --shallow-submodules "${REPO_URL}" "${SRC}"
	have="$(git -C "${SRC}" rev-parse HEAD)"
	if [[ "${have}" != "${PIN}" ]]; then
		echo "build-idevice.sh: tag ${TAG} resolved to ${have}, not the audited pin ${PIN}. HALT." >&2
		exit 1
	fi
fi

(
	cd "${SRC}"
	cargo build --locked --release -p idevice-ffi --target "${TARGET}" --no-default-features --features "${FEATURES}"
)

resolved="$(cd "${SRC}" && cargo tree --locked -p idevice-ffi --target "${TARGET}" --no-default-features --features "${FEATURES}" -e normal,build --prefix none)"
if [[ -z "${resolved}" ]]; then
	echo "build-idevice.sh: cargo tree printed nothing; cannot show the crypto backends are absent." >&2
	exit 1
fi
if grep -Eq '^(aws-lc-rs|aws-lc-sys|ring) ' <<< "${resolved}"; then
	echo "build-idevice.sh: the resolved build carries aws-lc-rs or ring; the audited feature set excludes both. HALT." >&2
	exit 1
fi
echo "build-idevice.sh: neither aws-lc-rs nor ring is in the resolved build"

archive="${CARGO_TARGET_DIR}/${TARGET}/release/libidevice_ffi.a"
if [[ ! -s "${archive}" ]]; then
	echo "build-idevice.sh: the build finished but ${archive} is missing or empty." >&2
	exit 1
fi

mkdir -p "${OUT}/lib" "${OUT}/include"
cp "${archive}" "${OUT}/lib/libidevice_ffi.a"
cp "${SRC}/ffi/idevice.h" "${OUT}/include/idevice.h"
printf 'module idevice {\n    header "idevice.h"\n    export *\n}\n' > "${OUT}/include/module.modulemap"

echo "build-idevice.sh: ${OUT}/lib/libidevice_ffi.a $(wc -c < "${OUT}/lib/libidevice_ffi.a" | tr -d ' ') bytes"
