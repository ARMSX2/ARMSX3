#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-only
# Builds and stages an installable IPA. Usage: bash ios/scripts/build-ios-ipa.sh [--help]
# Environment: SIGN=1 (default) uses Xcode signing for the keychain team. SIGN=0 builds for testers and signs ad hoc, because a re-signer
# copies the entitlements of the existing signature, and an unsigned IPA would lose the memory-limit key and abort at launch.
# CONFIGURATION is Release (default) or Debug; ARMSX3_CMAKE_CONFIG is ios-llvm (default) or ios-nollvm; TEAM_ID overrides the keychain team.
# OUT_DIR is where the IPA, DerivedData and the build log go (default ios/build/ipa).
# TESTER=1 builds a test build, whose JIT screen can open the menus without JIT; releases leave it at 0.
# It never touches a phone. Exit codes: 0 staged, 1 tool or build failure, 2 invalid value, 3 core archive missing or stale, 4 verification failed.

# --help runs before strict mode and any tool lookup, and prints the header with bash builtins only.
if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
	while IFS= read -r help_line; do
		[[ "${help_line}" == "#!"* ]] && continue
		[[ "${help_line}" == "#"* ]] || break
		help_line="${help_line#\#}"
		printf '%s\n' "${help_line# }"
	done < "${BASH_SOURCE[0]}"
	exit 0
fi

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "${ROOT_DIR}/.." && pwd)"

SIGN="${SIGN:-1}"
CONFIGURATION="${CONFIGURATION:-Release}"
ARMSX3_CMAKE_CONFIG="${ARMSX3_CMAKE_CONFIG:-ios-llvm}"
OUT_DIR="${OUT_DIR:-${ROOT_DIR}/build/ipa}"
TESTER="${TESTER:-0}"
SCHEME=ARMSX3
PROJECT_NAME=ARMSX3

EXIT_TOOL=1
EXIT_INVALID=2
EXIT_BLOCKED=3
EXIT_VERIFY=4

log() {
	printf '%s\n' "$*"
}

case "${TESTER}" in
	0 | 1) ;;
	*)
		echo "error: TESTER='${TESTER}' is not valid. Valid values: 0 (release, default), 1 (test build)." >&2
		exit "${EXIT_INVALID}"
		;;
esac
case "${SIGN}" in
	0 | 1) ;;
	*)
		echo "error: SIGN='${SIGN}' is not valid. Valid values: 1 (signed, default), 0 (unsigned)." >&2
		exit "${EXIT_INVALID}"
		;;
esac

case "${CONFIGURATION}" in
	Release | Debug) ;;
	*)
		echo "error: CONFIGURATION='${CONFIGURATION}' is not valid. Valid values: Release, Debug." >&2
		exit "${EXIT_INVALID}"
		;;
esac

case "${ARMSX3_CMAKE_CONFIG}" in
	ios-llvm | ios-nollvm) ;;
	*)
		echo "error: ARMSX3_CMAKE_CONFIG='${ARMSX3_CMAKE_CONFIG}' is not valid. Valid values: ios-llvm, ios-nollvm." >&2
		exit "${EXIT_INVALID}"
		;;
esac

# A missing archive and a stale one need different remedies, so they get distinct messages.

CORE_ARCHIVE_REL="ios/build/${ARMSX3_CMAKE_CONFIG}/libarmsx3_ios_core.a"

if [[ ! -f "${REPO_ROOT}/${CORE_ARCHIVE_REL}" ]]; then
	log "BLOCKED: no merged emulator-core archive for configuration ${ARMSX3_CMAKE_CONFIG}."
	log "  Expected: ${CORE_ARCHIVE_REL}"
	log "  Build and merge it:"
	log "    cmake --build ios/build/${ARMSX3_CMAKE_CONFIG} --target rpcs3_emu armsx3_bridge armsx3_ios_render"
	log "    bash ios/scripts/collect-ios-archives.sh ${ARMSX3_CMAKE_CONFIG}"
	log "  If the tree itself is unconfigured, run: bash ios/configure.sh ${ARMSX3_CMAKE_CONFIG}"
	log "  There is no override."
	exit "${EXIT_BLOCKED}"
fi

# /usr/bin/find is pinned because find on PATH can be bfs; no || true, so a find that cannot run aborts.
STALE_SOURCES="$(cd "${REPO_ROOT}" && /usr/bin/find ios/bridge ios/core -type f \
	\( -name '*.cpp' -o -name '*.cc' -o -name '*.cxx' -o -name '*.c' \
	   -o -name '*.mm' -o -name '*.m' -o -name '*.h' -o -name '*.hpp' \) \
	-newer "${CORE_ARCHIVE_REL}" -print | sort)"

if [[ -n "${STALE_SOURCES}" ]]; then
	log "BLOCKED: ${CORE_ARCHIVE_REL} is OLDER than port-owned sources it carries:"
	while IFS= read -r stale_path; do
		log "    ${stale_path}"
	done <<<"${STALE_SOURCES}"
	log "  xcodebuild cannot fix this; the app force-loads the archive. Rebuild it:"
	log "    cmake --build ios/build/${ARMSX3_CMAKE_CONFIG} --target rpcs3_emu armsx3_bridge armsx3_ios_render"
	log "    bash ios/scripts/collect-ios-archives.sh ${ARMSX3_CMAKE_CONFIG}"
	log "  There is no override: an IPA over a stale core is a plausible, wrong artefact."
	exit "${EXIT_BLOCKED}"
fi

if [[ ! -f "${ROOT_DIR}/app/ARMSX3.entitlements" ]]; then
	echo "error: ios/app/ARMSX3.entitlements is missing." >&2
	exit "${EXIT_INVALID}"
fi

TOOLS=(xcodebuild xcodegen xcrun ditto zip codesign plutil)
if [[ "${SIGN}" == 1 ]]; then
	TOOLS+=(security openssl)
fi
missing=0
for tool in "${TOOLS[@]}"; do
	if ! command -v "${tool}" >/dev/null 2>&1; then
		case "${tool}" in
			xcodegen) hint="brew install xcodegen" ;;
			openssl) hint="brew install openssl, or restore /usr/bin/openssl" ;;
			*) hint="install full Xcode, then: sudo xcode-select -s /Applications/Xcode.app/Contents/Developer" ;;
		esac
		echo "error: ${tool} was not found. Repair: ${hint}" >&2
		missing=1
	fi
done
[[ "${missing}" == 0 ]] || exit "${EXIT_TOOL}"

# The team comes from the keychain at run time and is never printed or written; use the OU, since the CN yields a certificate id and xcodebuild then fails with "No Account for Team".

if [[ "${SIGN}" == 1 ]]; then
	keychain_team_ous() {
		# security find-certificate -a -p concatenates PEM blocks and openssl x509 reads only the first, so split them here.
		local pem="" line
		while IFS= read -r line; do
			pem+="${line}"$'\n'
			if [[ "${line}" == "-----END CERTIFICATE-----" ]]; then
				printf '%s' "${pem}" \
					| openssl x509 -noout -subject -nameopt sep_multiline,utf8 2>/dev/null \
					| sed -n 's/^[[:space:]]*OU=\([A-Z0-9]\{10\}\)$/\1/p'
				pem=""
			fi
		done < <(security find-certificate -a -c "Apple Development" -p 2>/dev/null)
	}

	if [[ -z "${TEAM_ID:-}" ]]; then
		TEAM_CANDIDATES="$(keychain_team_ous | sort -u)"
		TEAM_COUNT="$(printf '%s' "${TEAM_CANDIDATES}" | grep -c . || true)"
		TEAM_ID="$(printf '%s\n' "${TEAM_CANDIDATES}" | head -1)"
		if [[ "${TEAM_COUNT}" -gt 1 ]]; then
			log "warning: ${TEAM_COUNT} distinct teams are present in the keychain; the first"
			log "         was taken. Set TEAM_ID explicitly if that is the wrong one."
		fi
	fi

	if [[ -z "${TEAM_ID}" ]]; then
		echo "error: no Apple Development identity with a team OU was found in the keychain." >&2
		echo "Add an Apple ID under Xcode > Settings > Accounts and let Xcode create a" >&2
		echo "free Apple Development certificate, then re-run. If the count is 0 but a" >&2
		echo "certificate exists, the WWDR G3 intermediate is probably missing. Confirm with:" >&2
		echo "  security find-identity -v -p codesigning" >&2
		exit "${EXIT_TOOL}"
	fi
	log "team: derived at run time (not recorded)"
fi

# The helper must be in the IPA and the embed is opt-in, so switch it on for this generate.

export ARMSX3_CMAKE_CONFIG PROJECT_NAME
export ARMSX3_JIT_ENABLER_EMBED=1
bash "${ROOT_DIR}/scripts/generate.sh"

mkdir -p "${OUT_DIR}"
OUT_DIR="$(cd "${OUT_DIR}" && pwd)"
DERIVED="${OUT_DIR}/DerivedData"
BUILD_LOG="${OUT_DIR}/xcodebuild-${CONFIGURATION}.log"

build_app() {
	local tester_flags=()
	[[ "${TESTER}" == 1 ]] && tester_flags=(SWIFT_ACTIVE_COMPILATION_CONDITIONS='$(inherited) ARMSX3_TESTER')
	if [[ "${SIGN}" == 1 ]]; then
		if ! xcodebuild -project "${ROOT_DIR}/${PROJECT_NAME}.xcodeproj" -scheme "${SCHEME}" -configuration "${CONFIGURATION}" -destination 'generic/platform=iOS' -derivedDataPath "${DERIVED}" -allowProvisioningUpdates DEVELOPMENT_TEAM="${TEAM_ID}" CODE_SIGN_STYLE=Automatic ${tester_flags[@]+"${tester_flags[@]}"} build > "${BUILD_LOG}" 2>&1; then
			tail -30 "${BUILD_LOG}" >&2
			echo "error: xcodebuild failed (signed)." >&2
			exit "${EXIT_TOOL}"
		fi
	else
		if ! xcodebuild -project "${ROOT_DIR}/${PROJECT_NAME}.xcodeproj" -scheme "${SCHEME}" -configuration "${CONFIGURATION}" -destination 'generic/platform=iOS' -derivedDataPath "${DERIVED}" CODE_SIGNING_ALLOWED=NO ${tester_flags[@]+"${tester_flags[@]}"} build > "${BUILD_LOG}" 2>&1; then
			tail -30 "${BUILD_LOG}" >&2
			echo "error: xcodebuild failed (unsigned)." >&2
			exit "${EXIT_TOOL}"
		fi
	fi
}

log "build: configuration=${CONFIGURATION} core=${ARMSX3_CMAKE_CONFIG} sign=${SIGN} (log: ${BUILD_LOG#"${REPO_ROOT}/"})"
build_app

APP="${DERIVED}/Build/Products/${CONFIGURATION}-iphoneos/${SCHEME}.app"

# Rebuild once if JITEnabler.appex no longer verifies, as when a renewed profile was copied in without a re-sign; the nested verify below still decides.
if [[ "${SIGN}" == 1 && -d "${APP}/PlugIns/JITEnabler.appex" ]] && ! codesign --verify --strict "${APP}/PlugIns/JITEnabler.appex" > /dev/null 2>&1; then
	log "JITEnabler.appex does not verify; removing its build products and building once more"
	cp "${BUILD_LOG}" "${BUILD_LOG}.first"
	/usr/bin/find "${DERIVED}/Build/Products" -name JITEnabler.appex -prune -exec rm -rf {} +
	build_app
fi

verify_fail() {
	echo "VERIFY FAILED: $*" >&2
	exit "${EXIT_VERIFY}"
}

# Logs only the presence of each key, since the XML carries the team id; either get-task-allow spelling counts, because Xcode embeds the bare one.
require_entitlements() {
	local bundle="$1" xml key state label missing=0
	shift
	xml="$(codesign -d --entitlements - --xml "${bundle}" 2>/dev/null || true)"
	label="${SCHEME}.app"
	[[ "${bundle}" == "${APP}" ]] || label+="/${bundle#"${APP}/"}"
	for key in "$@"; do
		if [[ "${xml}" == *"<key>${key}</key>"* || "${xml}" == *"<key>com.apple.security.${key}</key>"* ]]; then state=present; else state=absent; missing=1; fi
		log "${label} ${key##*.}=${state}"
	done
	return "${missing}"
}

# The SIGN=0 entitlements: what Xcode's SIGN=1 route embeds minus the team keys, with the bare get-task-allow instead of the macOS spellings.
adhoc_entitlements() {
	local key
	cp "$1" "$2"
	for key in 'com\.apple\.security\.get-task-allow' 'com\.apple\.security\.cs\.allow-jit' 'com\.apple\.security\.cs\.allow-unsigned-executable-memory'; do
		if plutil -extract "${key}" raw -o - "$2" >/dev/null 2>&1; then
			plutil -remove "${key}" "$2"
		fi
	done
	plutil -replace get-task-allow -bool YES "$2"
}

[[ -f "${APP}/${SCHEME}" ]] || verify_fail "no ${SCHEME}.app/${SCHEME} binary in the build products."
[[ -d "${APP}/PlugIns/JITEnabler.appex" ]] || verify_fail "PlugIns/JITEnabler.appex is absent; the IPA would ship without the JIT helper."

EXPORTS="$(xcrun dyld_info -exports "${APP}/${SCHEME}" | grep -c armsx3_jit_enabler || true)"
# Five names, resolved by dlsym from the dual map: prepare, prepare_ranges, last_result, ddi and pairing_report; armsx3_jit_launch_plan is checked separately.
if [[ "${EXPORTS}" != 5 ]]; then
	verify_fail "the binary exports ${EXPORTS} armsx3_jit_enabler_* names, expected 5. They are resolved by dlsym, so stripping removes them silently; check STRIP_STYLE in ios/project.yml."
fi
if [[ "$(xcrun dyld_info -exports "${APP}/${SCHEME}" | grep -c armsx3_jit_launch_plan || true)" != 1 ]]; then
	verify_fail "the binary does not export armsx3_jit_launch_plan, which the dual map resolves by dlsym before main(); check STRIP_STYLE in ios/project.yml."
fi

# Ad-hoc signing with no identity, keychain or --deep: nested code first, then the app, the order Xcode signs in.

if [[ "${SIGN}" == 0 ]]; then
	ADHOC_APP_ENTS="${OUT_DIR}/adhoc-app.entitlements"
	ADHOC_APPEX_ENTS="${OUT_DIR}/adhoc-appex.entitlements"
	# The shared DerivedData can hold the owner's provisioning profile from a SIGN=1 build, which names the team and devices.
	/usr/bin/find "${APP}" -name embedded.mobileprovision -delete
	# The same sources Xcode is given under SIGN=1.
	adhoc_entitlements "${ROOT_DIR}/app/ARMSX3.entitlements" "${ADHOC_APP_ENTS}"
	adhoc_entitlements "${ROOT_DIR}/jit-enabler/JITEnabler.entitlements" "${ADHOC_APPEX_ENTS}"
	# Dylibs are signed ad hoc without entitlements, then appexes, then the app; a framework stays unsigned and the verify loop refuses it.
	for dylib in "${APP}"/Frameworks/*.dylib; do
		[[ -e "${dylib}" ]] || continue
		codesign --force --sign - "${dylib}"
	done
	while IFS= read -r nested; do
		codesign --force --sign - --entitlements "${ADHOC_APPEX_ENTS}" "${nested}"
	done < <(/usr/bin/find "${APP}/PlugIns" -mindepth 1 -maxdepth 1 -name '*.appex' -print)
	codesign --force --sign - --entitlements "${ADHOC_APP_ENTS}" "${APP}"
fi

# Verify nested code first, then the bundle, so an embed cannot ship unsigned.
while IFS= read -r nested; do
	codesign --verify --strict "${nested}" || verify_fail "nested signature does not verify: ${nested#"${APP}/"}"
	log "signature ok: ${nested#"${APP}/"}"
	[[ "${nested}" != *.appex ]] || require_entitlements "${nested}" get-task-allow || verify_fail "the JIT helper lost get-task-allow: ${nested#"${APP}/"}"
done < <(/usr/bin/find "${APP}" \( -name '*.appex' -o -name '*.dylib' -o -name '*.framework' \) -prune)
codesign --verify --deep --strict "${APP}" || verify_fail "the app's signature does not verify (--deep --strict)."
log "signature ok: ${SCHEME}.app"

require_entitlements "${APP}" get-task-allow com.apple.developer.kernel.increased-memory-limit || verify_fail "a required entitlement is absent from the app; a staged IPA without the memory key aborts at launch."
[[ "${SIGN}" == 1 || -z "$(/usr/bin/find "${APP}" -name embedded.mobileprovision -print)" ]] || verify_fail "the SIGN=0 bundle still holds a provisioning profile, which names the team and its devices."

NAME="ARMSX3-${CONFIGURATION}-${ARMSX3_CMAKE_CONFIG}"
[[ "${SIGN}" == 1 ]] || NAME+="-unsigned"
# A test build must never pass for a release.
[[ "${TESTER}" == 0 ]] || NAME+="-tester"
NAME+=".ipa"
IPA="${OUT_DIR}/${NAME}"

IPA_TMP=""
cleanup() {
	[[ -n "${IPA_TMP}" && -d "${IPA_TMP}" ]] && rm -rf "${IPA_TMP}"
	return 0
}
trap cleanup EXIT

IPA_TMP="$(mktemp -d)"
mkdir -p "${IPA_TMP}/Payload"
# ditto keeps symlinks and extended attributes; zip -y stores symlinks as links instead of following them.
ditto "${APP}" "${IPA_TMP}/Payload/${SCHEME}.app"
rm -f "${IPA}"
( cd "${IPA_TMP}" && zip -q -r -y -X "${IPA}" Payload )

IPA_REL="${IPA#"${REPO_ROOT}/"}"
[[ "${IPA_REL}" != /* ]] || IPA_REL="(outside-repo)/${NAME}"
echo "@@IPA@@ sign=${SIGN} configuration=${CONFIGURATION} core=${ARMSX3_CMAKE_CONFIG} exports=${EXPORTS} appex=1 bytes=$(stat -f %z "${IPA}") path=${IPA_REL}"
