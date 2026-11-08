# SPDX-License-Identifier: GPL-2.0-only
# Sourced, never executed, by build-llvm-ios.sh so the cross-build compiles a patched copy of the pinned LLVM.
# It exports the pinned 3rdparty/llvm/llvm commit (llvm/ minus llvm/test, plus cmake/ and third-party/) and applies
# 3rdparty/llvm/armsx3-aarch64-ghc-emergency-spill.patch, which gives a ghccc function an emergency spill slot on AArch64.
# An export, not a worktree: git archive reads the local object store, writes no git state and leaves the submodule clean.
# The caller sets REPO_DIR and runs under set -euo pipefail. Defines LLVM_GHC_PATCH, LLVM_GHC_PATCH_SHA256 and
# llvm_patched_source DEST, which exports and patches into DEST unless DEST's stamp already matches.

LLVM_GHC_PATCH="${REPO_DIR}/3rdparty/llvm/armsx3-aarch64-ghc-emergency-spill.patch"
[[ -f "${LLVM_GHC_PATCH}" ]] || {
	echo "error: ${LLVM_GHC_PATCH} is missing. Every LLVM this port builds carries that patch, so refusing to build without it." >&2
	exit 1
}
LLVM_GHC_PATCH_SHA256="$(shasum -a 256 "${LLVM_GHC_PATCH}" | cut -d' ' -f1)"

llvm_patched_source()
{
	local dest="$1"
	local submodule="${REPO_DIR}/3rdparty/llvm/llvm"
	local pin expected parent
	local pathspec="llvm cmake third-party :(exclude)llvm/test"

	pin="$(git -C "${REPO_DIR}" rev-parse HEAD:3rdparty/llvm/llvm)"
	git -C "${submodule}" cat-file -e "${pin}^{tree}" 2>/dev/null || {
		echo "error: the LLVM submodule does not hold the pinned commit ${pin}. Run 'ios/scripts/bootstrap-submodules.sh --with-llvm 3rdparty/llvm/llvm'. Nothing is downloaded by this script." >&2
		exit 1
	}

	expected="$(printf 'pin=%s\npatch_sha256=%s\npathspec=%s\n' "${pin}" "${LLVM_GHC_PATCH_SHA256}" "${pathspec}")"
	if [[ -f "${dest}/.armsx3-src-stamp" && "$(cat "${dest}/.armsx3-src-stamp")" == "${expected}" ]]; then
		echo "==> llvm: patched source already exported at ${dest}"
		return 0
	fi

	echo "==> llvm: exporting ${pin} and applying $(basename "${LLVM_GHC_PATCH}") into ${dest}"
	rm -rf "${dest}"
	mkdir -p "${dest}"
	git -C "${submodule}" archive "${pin}" -- llvm cmake third-party ':(exclude)llvm/test' | tar -x -C "${dest}"

	# Measured trap: without the ceiling, git apply inside the superproject's tree exits 0 having applied nothing.
	parent="$(cd "${dest}/.." && pwd)"
	(cd "${dest}" && GIT_CEILING_DIRECTORIES="${parent}" git apply "${LLVM_GHC_PATCH}")

	local target="${dest}/llvm/lib/Target/AArch64/AArch64FrameLowering.cpp"
	grep -q 'RS->addScavengingFrameIndex(FI)' "${target}" && grep -q 'GHCMFI' "${target}" || {
		echo "error: the patch did not land in ${target}; refusing to build from an unpatched export." >&2
		exit 1
	}

	# Written LAST, so an interrupted export is never mistaken for a good one.
	printf '%s\n' "${expected}" > "${dest}/.armsx3-src-stamp"
}
