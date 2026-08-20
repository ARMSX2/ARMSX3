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

# Gradle needs a JDK and the shell this is run from may not have one on PATH. Android Studio
# ships one; fall back to it rather than failing several steps later with "Unable to locate a
# Java Runtime", which does not point at the cause.
if [ -z "${JAVA_HOME:-}" ]; then
	for candidate in \
		"/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
		"$(/usr/libexec/java_home 2>/dev/null || true)"
	do
		[ -x "$candidate/bin/java" ] && { export JAVA_HOME="$candidate"; break; }
	done
fi
[ -n "${JAVA_HOME:-}" ] || { echo "FAIL: no JDK found; set JAVA_HOME" >&2; exit 1; }
export PATH="$JAVA_HOME/bin:$PATH"

# Refuse to build at all without an upload key. A debug-signed bundle is rejected by Play, and
# finding that out at upload time after a fifteen-minute build is a poor way to learn it.
if [ ! -f "$UI/keystore.properties" ] || grep -q "REPLACE_ME\|REPLACE_WITH_ABSOLUTE_PATH" "$UI/keystore.properties"; then
	cat >&2 <<'MSG'
FAIL: upload key not configured (file missing, or placeholders not filled in).

Create android/armsx3-ui/keystore.properties with:

    storeFile=/absolute/path/to/upload.jks
    storePassword=...
    keyAlias=upload
    keyPassword=...

and generate the key itself with:

    keytool -genkeypair -v -keystore upload.jks -alias upload \
        -keyalg RSA -keysize 4096 -validity 10000

Keep upload.jks and its passwords safe and backed up: Play ties the listing to this key
and losing it means losing the ability to update the app. Both the keystore and the
properties file are gitignored.
MSG
	exit 1
fi

# armsx3.noMinify because AGP 9.2.1 cannot bundle with R8 on: R8 writes mapping.prt, a
# compressed per-class archive, and packageBundle demands a plain mapping.txt. ARMSX2 ships
# its Play build with minify off too, so this is the existing precedent rather than a new
# compromise.
# Stage the LEGACY core, and do not trust whatever happens to be in jniLibs.
#
# build-variants.sh writes each variant's core to the same path in turn, so the file left there
# is simply whichever variant ran last. A bundle built on top of that would ship an armv8.2 core
# with minSdk 30 -- installable on devices that cannot execute it, and failing at dlopen with
# nothing to explain why. Play serves one bundle to every device, so the ISA floor has to be the
# lowest one ARMSX3 supports.
CORE_SRC="$(cd "$HERE/.." && pwd)/build-legacy/android/libarmsx3-core.so"
JNI="$UI/app/src/main/jniLibs/arm64-v8a"

if [ ! -f "$CORE_SRC" ]; then
	echo "FAIL: legacy core not built. Run: ninja -C build-legacy android/libarmsx3-core.so" >&2
	exit 1
fi

NDK_DIR="$(ls -d "$ANDROID_HOME/ndk/"*/ 2>/dev/null | sort -V | tail -1)"
STRIP="${NDK_DIR}toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip"
[ -x "$STRIP" ] || { echo "FAIL: llvm-strip not found under $ANDROID_HOME/ndk" >&2; exit 1; }

# Same reason as build-variants.sh: cmake only regenerates this at configure time.
bash "$HERE/stamp-git-version.sh"

echo "==> Staging the legacy core"
mkdir -p "$JNI"
"$STRIP" --strip-unneeded -o "$JNI/libarmsx3-core.so" "$CORE_SRC"

echo "==> Building Play bundle (minSdk $MIN_SDK, minify off)"
( cd "$UI" && ./gradlew --quiet :app:bundlePlayRelease "-Parmsx3.minSdk=$MIN_SDK" -Parmsx3.noMinify )

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
#
# Captured ONCE into a variable rather than piped into each grep. Under `set -o pipefail`,
# `unzip -l | grep -q x` fails whenever x IS found: grep exits at the first match, closes the
# pipe, unzip takes SIGPIPE, and the pipeline reports failure. That inverted every positive
# check -- it reported the core library missing from a bundle that plainly contained it, while
# the absence checks passed for the wrong reason, because grep read to the end and found
# nothing.
LISTING="$(unzip -l "$AAB")"

# Matched with `case`, not with a pipe into grep. Under `set -o pipefail` any `... | grep -q x`
# FAILS when x is found: grep exits at the first match, closes the pipe, and whatever is feeding
# it takes SIGPIPE. That inverted every positive check -- the core library was reported missing
# from a bundle that plainly contained it, while the absence checks passed for the wrong reason,
# because grep read to the end and found nothing. Piping printf instead of unzip moved the
# broken pipe rather than removing it; case has no subprocess to signal.
if [[ "$LISTING" == *libarmsx3_lsfg.so* ]]; then
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

if [[ "$LISTING" == *libarmsx3-core.so* ]]; then
	echo "  ok: core library present"
else
	echo "FAIL: libarmsx3-core.so missing from the bundle" >&2
	fail=1
fi

[ "$fail" -eq 0 ] || { echo "==> REFUSING to ship this bundle" >&2; exit 1; }

OUT="$OUT_DIR/ARMSX3-$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$UI/app/build.gradle.kts" | head -1)-play.aab"
cp "$AAB" "$OUT"
echo "==> $OUT"
