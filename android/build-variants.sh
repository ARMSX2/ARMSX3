#!/usr/bin/env bash
#
# Build the release variants of ARMSX3.
#
# ARMSX3 ships four APKs. The split is by platform level and ISA baseline -- the core is
# arm64-only in all four:
#
#   legacy  API 30 (Android 11), armv8.1-a               the fallback, and the FLOOR the code
#                                                        compiles at: util/simd.hpp uses SQRDMLAH
#                                                        (v8.1 RDMA) and util/asm.hpp has inline
#                                                        LSE atomics, so armv8-a does not build.
#                                                        Its value is devices whose cores are 8.2
#                                                        WITHOUT the optional fp16/dotprod that the
#                                                        other three builds require.
#                                                        (Cortex-A53/A57/A73) have neither
#                                                        dotprod nor FP16, so this stays at the
#                                                        8.1 baseline and keeps only LSE atomics.
#   a11     API 30 (Android 11), armv8.2-a+dotprod+fp16
#   a13     API 33 (Android 13), armv8.2-a+dotprod+fp16  the standard build; 8 Gen 1 and later
#   a15     API 35 (Android 15), armv8.2-a+dotprod+fp16  current devices
#
# The three 8.2 builds differ ONLY in platform level, so they generate the same code and exist
# to widen the floor, not to go faster than each other. What a higher API buys is what the
# platform itself provides -- native ELF TLS from 29 up being the one that touches the emulator
# core -- so a device should install the highest variant it can run.
#
# +fp16 was briefly suspected of breaking Batman: Arkham City and is cleared. That failure was
# the Qualcomm proprietary driver running the device out of memory -- RAM climbed monotonically
# to 7.1GB and lowmemorykiller took the process, with no signal in any log. The same build is
# fine on Turnip. See the per-vkCmdEndRenderPass leak note in vkutils/device.cpp.
#
# All three build with NDK 29. The NDK is not a device-compatibility knob -- what gates devices
# is the API level and -march, and a device cannot tell which toolchain produced its binary. NDK
# 28 was tried for the older two and does not compile this tree at all: fmt::throw_exception is a
# class whose constructor and destructor are [[noreturn]], and clang 19 (NDK 28) does not
# propagate that through a temporary, so every function ending in a throw fails with "does not
# return a value in all control paths". Clang 21 (NDK 29) handles it. Satisfying the older
# compiler would mean adding unreachable returns across upstream code and re-doing it on every
# merge, for a toolchain that generates worse code anyway.
#
# On the ISA: LSE atomics come from the 8.1 baseline, so every variant has them. dotprod and
# FP16 are named explicitly on the 8.2 builds because 8.2 makes neither mandatory -- dotprod
# only becomes so at 8.4. That is a hard floor, not a preference: those instructions can appear
# anywhere in the C++ and a device without them faults rather than falling back, which is the
# whole reason the generic variant exists.
#
# Each core lives in its own build directory and shares no objects with the others. Every one
# is a full LLVM + RPCS3 build from cold, so all three together run for hours. They are built
# one after another on purpose: concurrent ninja jobs thrash each other and finish slower than
# in sequence.
#
# Usage:
#   android/build-variants.sh                 # all three
#   android/build-variants.sh a13             # one
#   android/build-variants.sh a13 a15         # some
#   VARIANTS="generic" android/build-variants.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
: "${JAVA_HOME:=/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
: "${CMAKE_VERSION:=3.30.5}"
: "${OUT_DIR:=$HOME/Downloads}"
export ANDROID_HOME JAVA_HOME

CMAKE_BIN="$ANDROID_HOME/cmake/$CMAKE_VERSION/bin"
UI="$ROOT/android/armsx3-ui"
JNI_LIBS="$UI/app/src/main/jniLibs/arm64-v8a"
# Frame generation ships in the github flavor only, so its library lives in that source set.
# The play bundle must not contain it: excluding the file IS the exclusion, because the core
# dlopen's it by name and reports the feature unavailable when it is absent.
JNI_LIBS_GITHUB="$UI/app/src/github/jniLibs/arm64-v8a"

# variant : ndk : api : march : apk name suffix
#
# The suffix is spelled out rather than derived so the filename states the whole contract --
# platform level and ISA baseline both -- to someone picking a download with no release notes
# in front of them. It is also what the in-app updater matches on, so changing one of these
# strings changes which APK an existing install offers; keep them in step with pickApkAsset
# in UpdaterEntry.kt.
VARIANT_legacy="29.0.14206865:30:armv8.1-a:legacy-armv8.1-sdk30"
VARIANT_a11="29.0.14206865:30:armv8.2-a+dotprod+fp16:a11-armv8.2-sdk30"
VARIANT_a13="29.0.14206865:33:armv8.2-a+dotprod+fp16:a13-armv8.2-sdk33"
VARIANT_a15="29.0.14206865:35:armv8.2-a+dotprod+fp16:a15-armv8.2-sdk35"

VARIANTS="${VARIANTS:-${*:-legacy a11 a13 a15}}"

version_name() {
	sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$UI/app/build.gradle.kts" | head -1
}

build_variant() {
	local name="$1"
	local spec_var="VARIANT_${name}"
	local spec="${!spec_var:-}"

	if [[ -z "$spec" ]]; then
		echo "unknown variant '$name' (expected legacy, a11, a13 or a15)" >&2
		return 1
	fi

	local ndk api march suffix
	IFS=: read -r ndk api march suffix <<<"$spec"
	local build_dir="$ROOT/build-$name"

	echo "==> $name: NDK $ndk, API $api, -march=$march"

	# Does build.ninja already describe exactly the toolchain we want?
	#
	# The NDK bakes the target triple at first configure, so a warm build directory silently keeps
	# the OLD api level -- which is exactly how you end up shipping an "API 35" build that is
	# really API 33. Both of these are checked whether or not we configure.
	configured_ok() {
		[ -f "$build_dir/build.ninja" ] &&
			grep -q "aarch64-none-linux-android$api" "$build_dir/build.ninja" &&
			grep -q -- "-march=$march" "$build_dir/build.ninja"
	}

	# Skip configure when the build directory already matches.
	#
	# Re-running cmake regenerates LLVM's generated headers. Their CONTENT is identical, but ninja
	# keys on mtime, so a one-line edit to our own code drags ~450 LLVM objects and a full relink
	# along with it: 20 minutes instead of two. That turns any bisection into an overnight job.
	#
	# Skipping is safe precisely because the check above is the same one that used to run after
	# configure -- if the directory does not already describe this exact API and -march we still
	# configure, and we still verify afterwards. What is NOT covered either way is a change to
	# configure.sh's own cmake arguments, since those do not appear in the two greps; delete the
	# build directory when that file changes.
	if configured_ok; then
		echo "==> $name: build dir already configured for API $api / $march, skipping cmake"
	else
		# configure.sh forwards "$@" straight to cmake, so the march override rides along there.
		NDK_VERSION="$ndk" ANDROID_API="$api" BUILD_DIR="$build_dir" \
			bash "$ROOT/android/configure.sh" "-DARMSX3_ARM_MARCH=$march"

		if ! configured_ok; then
			echo "$name: configure did not produce API $api / -march=$march -- stale build dir?" \
				"remove $build_dir" >&2
			return 1
		fi
	fi

	# Frame generation is a separate target on purpose.
	#
	# Nothing links libarmsx3_lsfg.so -- the core reaches it with dlopen, because framegen's volk
	# defines the same 655 vk* globals our renderer uses and linking them together would let
	# volkLoadDevice() repoint the whole renderer at framegen's device. The consequence for the
	# build is that it is NOT a dependency of libarmsx3-core.so and will not be built by asking
	# for it: name it here or ship an APK with frame generation silently missing.
	PATH="$CMAKE_BIN:$PATH" ninja -C "$build_dir" android/libarmsx3-core.so armsx3_lsfg

	local strip="$ANDROID_HOME/ndk/$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip"

	"$strip" --strip-unneeded -o "$JNI_LIBS/libarmsx3-core.so" \
		"$build_dir/android/libarmsx3-core.so"

	local lsfg_so="$build_dir/3rdparty/lsfg/libarmsx3_lsfg.so"

	if [[ -f "$lsfg_so" ]]; then
		mkdir -p "$JNI_LIBS_GITHUB"
		"$strip" --strip-unneeded -o "$JNI_LIBS_GITHUB/libarmsx3_lsfg.so" "$lsfg_so"

		# The isolation is the whole design, so verify it every build rather than trusting it.
		# Only the shim's own entry points may be dynamic: a single leaked vk* symbol means the
		# dynamic linker can bind the renderer's Vulkan calls to framegen's copies.
		local leaked
		leaked=$("$ANDROID_HOME/ndk/$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-nm" \
			-D --defined-only "$JNI_LIBS_GITHUB/libarmsx3_lsfg.so" 2>/dev/null | grep -cE "vk[A-Z]|LSFG" || true)

		if [[ "$leaked" != "0" ]]; then
			echo "$name: libarmsx3_lsfg.so exports $leaked Vulkan/LSFG symbols -- isolation broken," \
				"refusing to package it" >&2
			return 1
		fi
	else
		echo "$name: libarmsx3_lsfg.so was not built, frame generation will be absent from this APK" >&2
		rm -f "$JNI_LIBS_GITHUB/libarmsx3_lsfg.so"
	fi

	# assembleGithubRelease, not assembleRelease: the flavor split means there is no
	# flavorless release variant any more. The play bundle is built by build-play-aab.sh.
	( cd "$UI" && ./gradlew --quiet :app:assembleGithubRelease "-Parmsx3.minSdk=$api" )

	local out="$OUT_DIR/ARMSX3-$(version_name)-$suffix.apk"
	cp "$UI/app/build/outputs/apk/github/release/app-github-release.apk" "$out"
	echo "==> $name: $out"
}

# Stamp the version before anything builds, or every APK reports whichever commit cmake
# last configured against rather than the one being built.
bash "$ROOT/android/stamp-git-version.sh"

for v in $VARIANTS; do
	build_variant "$v"
done

echo
echo "Done. Both variants are the same source at the same versionName -- they are alternatives"
echo "for different devices, not an upgrade path, so publish them side by side and let the user"
echo "pick. If the in-app updater ever offers one to a user running the other, that is a release"
echo "metadata problem, not a build one."
