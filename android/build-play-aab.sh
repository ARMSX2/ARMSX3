#!/usr/bin/env bash
#
# Build the Google Play bundle, and refuse to produce one that Play would reject.
#
# The legacy variant is what goes to Play: armv8.1-a and minSdk 30 is the widest floor ARMSX3
# has, and Play serves one bundle to every device, so the lowest floor reaches the most people.
# targetSdk is 37 for every variant, so the API-level requirement is met regardless.
#
# The checks below are the point of this script. A flavor split is only as good as the thing
# that notices when it stops working, and every one of these has a specific failure behind it:
#
#   REQUEST_INSTALL_PACKAGES  a self-updating app is a hard policy violation, and it is the
#                             declared permission that gets rejected, not the code path
#   MANAGE_EXTERNAL_STORAGE   all-files access needs a declared exemption the app does not need
#   RECORD_AUDIO              would put "Microphone" on the listing for a capability not shipped
#   libarmsx3_lsfg.so         frame generation is not distributed through Play
#   updateprovider            the FileProvider that hands the downloaded APK to the installer
#
# Fails closed: if a check cannot run, that is a failure too.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UI="$HERE/armsx3-ui"
: "${OUT_DIR:=$HOME/Downloads}"
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"

MIN_SDK="${PLAY_MIN_SDK:-30}"

echo "==> Building Play bundle (minSdk $MIN_SDK)"
( cd "$UI" && ./gradlew --quiet :app:bundlePlayRelease "-Parmsx3.minSdk=$MIN_SDK" )

AAB="$UI/app/build/outputs/bundle/playRelease/app-play-release.aab"
[ -f "$AAB" ] || { echo "FAIL: no bundle produced at $AAB" >&2; exit 1; }

echo "==> Verifying the bundle"

# An AAB is a ZIP and its entries are COMPRESSED, so grepping the archive itself finds
# nothing and reports a clean bundle no matter what is in it. That is not a theoretical
# risk: the first version of this script did exactly that and passed every check while
# also failing to find the applicationId it was supposed to find, which is what gave it
# away. Extract first, then inspect the manifest and the file list.
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
unzip -q -o "$AAB" -d "$WORK"

MANIFEST="$WORK/base/manifest/AndroidManifest.xml"
[ -f "$MANIFEST" ] || { echo "FAIL: no manifest in the bundle" >&2; exit 1; }

fail=0

# The manifest is protobuf-encoded, so permission names appear as plain strings inside it
# but the file contains NUL bytes -- LC_ALL=C and -a keep grep from calling it binary and
# silently saying nothing, which reads exactly like a pass.
check_absent_manifest() {
	local needle="$1" why="$2"
	if LC_ALL=C grep -aqF "$needle" "$MANIFEST"; then
		echo "FAIL: '$needle' is declared in the bundle -- $why" >&2
		fail=1
	else
		echo "  ok: $needle absent from the manifest"
	fi
}

check_absent_manifest "REQUEST_INSTALL_PACKAGES" "Play forbids self-updating apps"
check_absent_manifest "MANAGE_EXTERNAL_STORAGE"  "all-files access needs a declared exemption"
check_absent_manifest "RECORD_AUDIO"             "would add Microphone to the listing"
check_absent_manifest "updateprovider"           "the updater FileProvider must not ship"

# Native libraries are entries in the archive, so check the listing rather than the bytes.
if unzip -l "$AAB" | grep -q "libarmsx3_lsfg.so"; then
	echo "FAIL: libarmsx3_lsfg.so is in the bundle -- frame generation is not shipped through Play" >&2
	fail=1
else
	echo "  ok: libarmsx3_lsfg.so absent"
fi

# And the things that MUST be there.
if LC_ALL=C grep -aqF "com.armsx3.play" "$MANIFEST"; then
	echo "  ok: applicationId is com.armsx3.play"
else
	echo "FAIL: applicationId com.armsx3.play not in the manifest -- wrong flavor built?" >&2
	fail=1
fi

if unzip -l "$AAB" | grep -q "libarmsx3-core.so"; then
	echo "  ok: core library present"
else
	echo "FAIL: libarmsx3-core.so missing from the bundle" >&2
	fail=1
fi

[ "$fail" -eq 0 ] || { echo "==> REFUSING to ship this bundle" >&2; exit 1; }

OUT="$OUT_DIR/ARMSX3-$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$UI/app/build.gradle.kts" | head -1)-play.aab"
cp "$AAB" "$OUT"
echo "==> $OUT"
