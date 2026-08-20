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

# The manifest inside an AAB is binary XML, so grep it as binary rather than as text.
# LC_ALL=C and -a matter: without them a NUL byte makes grep call the file binary and stay quiet,
# which reads exactly like a pass.
fail=0

check_absent() {
	local needle="$1" why="$2"
	if LC_ALL=C grep -aqF "$needle" "$AAB"; then
		echo "FAIL: '$needle' is present in the bundle -- $why" >&2
		fail=1
	else
		echo "  ok: $needle absent"
	fi
}

check_absent "REQUEST_INSTALL_PACKAGES" "Play forbids self-updating apps"
check_absent "MANAGE_EXTERNAL_STORAGE"  "all-files access needs a declared exemption"
check_absent "RECORD_AUDIO"             "would add Microphone to the listing"
check_absent "libarmsx3_lsfg.so"        "frame generation is not shipped through Play"
check_absent "updateprovider"           "the updater's FileProvider must not ship"

# And the things that MUST be there.
if ! LC_ALL=C grep -aqF "com.armsx3.play" "$AAB"; then
	echo "FAIL: applicationId com.armsx3.play not found -- wrong flavor built?" >&2
	fail=1
else
	echo "  ok: applicationId is com.armsx3.play"
fi

if ! unzip -l "$AAB" | grep -q "libarmsx3-core.so"; then
	echo "FAIL: libarmsx3-core.so missing from the bundle" >&2
	fail=1
else
	echo "  ok: core library present"
fi

[ "$fail" -eq 0 ] || { echo "==> REFUSING to ship this bundle" >&2; exit 1; }

OUT="$OUT_DIR/ARMSX3-$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$UI/app/build.gradle.kts" | head -1)-play.aab"
cp "$AAB" "$OUT"
echo "==> $OUT"
