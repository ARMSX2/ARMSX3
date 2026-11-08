#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-only
# Generates ios/ARMSX3.xcodeproj from ios/project.yml; the project is build output and is not committed.
# Environment: PROJECT_NAME (default ARMSX3), SKIP_MOLTENVK (any value skips the pinned MoltenVK fetch) and
# ARMSX3_CMAKE_CONFIG (ios-nollvm by default, or ios-llvm), which picks the core archive the bundle links.
# It neither builds nor checks for that archive: run configure.sh, cmake --build and collect-ios-archives.sh first.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

PROJECT_NAME="${PROJECT_NAME:-ARMSX3}"
ARMSX3_CMAKE_CONFIG="${ARMSX3_CMAKE_CONFIG:-ios-nollvm}"

case "${ARMSX3_CMAKE_CONFIG}" in
	ios-nollvm | ios-llvm) ;;
	*)
		echo "error: ARMSX3_CMAKE_CONFIG='${ARMSX3_CMAKE_CONFIG}' is not a configuration." >&2
		echo "Valid values: ios-nollvm, ios-llvm. It selects which emulator core the bundle" >&2
		echo "links; an unrecognised value would be expanded into a path that silently does" >&2
		echo "not exist and fail much later, at link." >&2
		exit 1
		;;
esac

export ARMSX3_CMAKE_CONFIG

# Name the repair command with the failure, so the message can be acted on without a second document.

if ! command -v xcodebuild >/dev/null 2>&1; then
	echo "error: xcodebuild was not found. Install full Xcode from Apple, then run:" >&2
	echo "  sudo xcode-select -s /Applications/Xcode.app/Contents/Developer" >&2
	exit 1
fi

if ! command -v xcodegen >/dev/null 2>&1; then
	echo "error: xcodegen was not found. ios/project.yml is the project of record," >&2
	echo "so there is no checked-in .xcodeproj to fall back to. Install it with:" >&2
	echo "  brew install xcodegen" >&2
	exit 1
fi

if [[ ! -f "${ROOT_DIR}/app/ARMSX3.entitlements" ]]; then
	echo "error: entitlements file not found: ios/app/ARMSX3.entitlements" >&2
	exit 1
fi

# The app links MoltenVK, so fetch it (pinned, a no-op once present) before XcodeGen writes a project that references it.

if [[ -z "${SKIP_MOLTENVK:-}" ]]; then
	bash "${ROOT_DIR}/scripts/fetch-moltenvk.sh"
fi

cd "${ROOT_DIR}"

echo "xcodegen: project=${PROJECT_NAME} entitlements=app/ARMSX3.entitlements core=${ARMSX3_CMAKE_CONFIG}"
xcodegen generate --spec project.yml --project .

if [[ ! -d "${ROOT_DIR}/${PROJECT_NAME}.xcodeproj" ]]; then
	echo "error: xcodegen reported success but ${PROJECT_NAME}.xcodeproj is absent." >&2
	exit 1
fi

# Read the entitlements setting back out of the generated project instead of echoing what was requested.
granted="$(grep -o 'CODE_SIGN_ENTITLEMENTS = [^;]*' "${PROJECT_NAME}.xcodeproj/project.pbxproj" | sort -u | head -1)"
echo "generated: ${PROJECT_NAME}.xcodeproj  (${granted:-CODE_SIGN_ENTITLEMENTS unset})"
