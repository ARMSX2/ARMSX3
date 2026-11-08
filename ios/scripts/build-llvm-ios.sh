#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-only
# Usage: ios/scripts/build-llvm-ios.sh [--force | --help]
# Cross-builds LLVM for arm64-ios, AArch64 only, in two stages: a host llvm-tblgen bootstrap, then the arm64-ios build into ios/build-deps/llvm-ios-install/.
# ios/cmake/IosLlvm.cmake points LLVM_DIR at that install, so upstream's prebuilt-LLVM branch finds it. Stage 2 compiles a patched export of the pinned submodule commit.
# It is a script, not a CMake subproject, because the host tablegens need a second toolchain and a nested build would redo LLVM's host probes on every reconfigure.
# The target list is passed explicitly, since upstream's probe can fall back to X86, and nothing is pruned. Nothing is downloaded: an uninitialised submodule is an error naming bootstrap-submodules.sh.
# Environment: IOS_DEPLOYMENT_TARGET (default 15.0), BUILD_DEPS_DIR (default ios/build-deps), LLVM_JOBS (default CPU count), CMAKE. --force rebuilds even when the stamp matches.
# A stamp of the commit, patch hash, target, host triple and configure line lets a matching re-run exit at once.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${IOS_DIR}/.." && pwd)"

# shellcheck source=ios/scripts/llvm-patched-source.sh
source "${SCRIPT_DIR}/llvm-patched-source.sh"

# LLVM's CMake project is the llvm/ directory of the monorepo checkout at 3rdparty/llvm/llvm.
LLVM_SUBMODULE="${REPO_DIR}/3rdparty/llvm/llvm"
LLVM_SRC="${LLVM_SUBMODULE}/llvm"

# The target list, stated once and used for the stage-2 flag, the stamp and the record.
LLVM_TARGETS="AArch64"

usage()
{
	# The header comment is the usage text, printed up to the first line that is not a comment.
	awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
}

die()
{
	echo "error: $*" >&2
	exit 1
}

say()
{
	echo "==> $*"
}

FORCE=0

while [[ $# -gt 0 ]]; do
	case "$1" in
		--help|-h)
			usage
			exit 0
			;;
		--force)
			FORCE=1
			shift
			;;
		*)
			usage >&2
			die "unknown argument '$1'."
			;;
	esac
done

CMAKE="${CMAKE:-cmake}"

for tool in "${CMAKE}" ninja git xcrun lipo vtool ar; do
	command -v "${tool}" >/dev/null 2>&1 || die "${tool} was not found on PATH."
done

IOS_DEPLOYMENT_TARGET="${IOS_DEPLOYMENT_TARGET:-15.0}"

SDK_VERSION="$(xcrun --sdk iphoneos --show-sdk-version)"

if [[ -z "${LLVM_JOBS:-}" ]]; then
	LLVM_JOBS="$(sysctl -n hw.logicalcpu 2>/dev/null || echo 4)"
fi

# An uninitialised submodule is an error that names the bootstrap, never a fetch.

if [[ ! -f "${LLVM_SRC}/CMakeLists.txt" ]]; then
	die "the LLVM submodule is not initialised: ${LLVM_SRC}/CMakeLists.txt is missing. Run 'ios/scripts/bootstrap-submodules.sh --with-llvm 3rdparty/llvm/llvm' to initialise it at the commit the superproject pins. Nothing is downloaded by this script."
fi

LLVM_COMMIT="$(git -C "${REPO_DIR}" rev-parse HEAD:3rdparty/llvm/llvm 2>/dev/null || true)"
[[ -n "${LLVM_COMMIT}" ]] || die \
	"git cannot report the pinned commit of 3rdparty/llvm/llvm. The stamp names it, so refusing to build without it."

LLVM_VERSION="$(
	awk '
		/set\(LLVM_VERSION_MAJOR/ { major = $2 }
		/set\(LLVM_VERSION_MINOR/ { minor = $2 }
		/set\(LLVM_VERSION_PATCH/ { patch = $2 }
		END {
			gsub(/[^0-9]/, "", major); gsub(/[^0-9]/, "", minor); gsub(/[^0-9]/, "", patch)
			if (major != "") printf "%s.%s.%s\n", major, minor, patch
		}
	' "${LLVM_SUBMODULE}/cmake/Modules/LLVMVersion.cmake"
)"

[[ -n "${LLVM_VERSION}" ]] || die \
	"could not read the LLVM version from ${LLVM_SUBMODULE}/cmake/Modules/LLVMVersion.cmake. 3rdparty/llvm/CMakeLists.txt enforces a version floor of 18 on the find_package branch, so the version is not a detail this script may guess at."

BUILD_DEPS_DIR="${BUILD_DEPS_DIR:-${IOS_DIR}/build-deps}"

# Stage 2's source: the pinned commit, exported and patched by llvm-patched-source.sh.
PATCHED_SRC_ROOT="${BUILD_DEPS_DIR}/llvm-src-patched"
LLVM_PATCHED_SRC="${PATCHED_SRC_ROOT}/llvm"

# Stage 1's two binaries are copied to HOST_BIN, a small stable directory that survives a rm -rf of the build tree.
HOST_BUILD_DIR="${BUILD_DEPS_DIR}/llvm-host-build"
HOST_BIN="${BUILD_DEPS_DIR}/llvm-host/bin"

IOS_BUILD_DIR="${BUILD_DEPS_DIR}/llvm-ios"
PREFIX="${BUILD_DEPS_DIR}/llvm-ios-install"
STAMP="${PREFIX}/.armsx3-llvm-stamp"

# Assembled before the stamp comparison, because the stamp records it and a changed flag must invalidate the cache.

IOS_CONFIGURE_ARGS=(
	-G Ninja
	-DCMAKE_BUILD_TYPE=Release
	"-DCMAKE_INSTALL_PREFIX=${PREFIX}"
	# CMAKE_SYSTEM_NAME=iOS sets CMAKE_CROSSCOMPILING, so LLVM looks for host tools instead of building its own.
	-DCMAKE_SYSTEM_NAME=iOS
	-DCMAKE_OSX_SYSROOT=iphoneos
	-DCMAKE_OSX_ARCHITECTURES=arm64
	"-DCMAKE_OSX_DEPLOYMENT_TARGET=${IOS_DEPLOYMENT_TARGET}"
	# Do not pass CMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY here: link probes like check_library_exists would answer yes for every library, and LLVM would bake -lrt into the installed LLVMConfig.cmake.

	# LLVM_NATIVE_TOOL_DIR also covers llvm-min-tblgen, which LLVM 18 split out and a lone LLVM_TABLEGEN does not answer for.
	"-DLLVM_TABLEGEN=${HOST_BIN}/llvm-tblgen"
	"-DLLVM_NATIVE_TOOL_DIR=${HOST_BIN}"
	# Explicit rather than inferred, since upstream's probe can fall back to X86.
	"-DLLVM_TARGETS_TO_BUILD=${LLVM_TARGETS}"
	-DLLVM_TARGET_ARCH=AArch64
	"-DLLVM_DEFAULT_TARGET_TRIPLE=arm64-apple-ios${IOS_DEPLOYMENT_TARGET}"
	# State the host triple, or LLVM probes the cross target for host information.
	-DLLVM_HOST_TRIPLE=arm64-apple-macosx
	# Without this LLVM hands Apple's ld --gc-sections, because CMAKE_SYSTEM_NAME is iOS rather than Darwin, and llvm-tblgen fails to link at the end of the build.
	-DLLVM_NO_DEAD_STRIP=ON
	# Static archives with PIC, which is what STATIC_LINK_LLVM on the consumer side expects.
	-DLLVM_ENABLE_PIC=ON
	-DBUILD_SHARED_LIBS=OFF
	-DLLVM_BUILD_LLVM_DYLIB=OFF
	-DLLVM_LINK_LLVM_DYLIB=OFF
	# Close the host-sysroot door explicitly instead of leaving it to find_package.
	-DLLVM_ENABLE_ZLIB=OFF
	-DLLVM_ENABLE_ZSTD=OFF
	-DLLVM_ENABLE_LIBXML2=OFF
	-DLLVM_ENABLE_TERMINFO=OFF
	-DLLVM_ENABLE_LIBEDIT=OFF
	-DLLVM_ENABLE_LIBPFM=OFF
	-DLLVM_ENABLE_PLUGINS=OFF
	# Nothing that produces a target executable: the host cannot run it and signing has no target for it.
	-DLLVM_BUILD_TOOLS=OFF
	-DLLVM_BUILD_UTILS=OFF
	-DLLVM_BUILD_RUNTIME=OFF
	-DLLVM_BUILD_TESTS=OFF
	-DLLVM_BUILD_EXAMPLES=OFF
	-DLLVM_BUILD_BENCHMARKS=OFF
	-DLLVM_INCLUDE_TOOLS=OFF
	-DLLVM_INCLUDE_UTILS=OFF
	-DLLVM_INCLUDE_TESTS=OFF
	-DLLVM_INCLUDE_EXAMPLES=OFF
	-DLLVM_INCLUDE_BENCHMARKS=OFF
	-DLLVM_INCLUDE_DOCS=OFF
	-DLLVM_ENABLE_WARNINGS=OFF
	# Assertions are already off for a Release configure; passing it stops another build type from silently turning them on.
	-DLLVM_ENABLE_ASSERTIONS=OFF
)

IOS_CONFIGURE_LINE="${CMAKE} -S ${LLVM_PATCHED_SRC} -B ${IOS_BUILD_DIR} ${IOS_CONFIGURE_ARGS[*]}"

stamp_body()
{
	printf 'llvm_submodule_commit=%s\n' "${LLVM_COMMIT}"
	printf 'llvm_patch_sha256=%s\n' "${LLVM_GHC_PATCH_SHA256}"
	printf 'llvm_version=%s\n' "${LLVM_VERSION}"
	printf 'ios_deployment_target=%s\n' "${IOS_DEPLOYMENT_TARGET}"
	printf 'iphoneos_sdk=%s\n' "${SDK_VERSION}"
	printf 'llvm_targets_to_build=%s\n' "${LLVM_TARGETS}"
	printf 'llvm_host_triple=%s\n' "arm64-apple-macosx"
	printf 'configure=%s\n' "${IOS_CONFIGURE_LINE}"
}

install_present()
{
	[[ -f "${PREFIX}/lib/cmake/llvm/LLVMConfig.cmake" ]] || return 1
	[[ -f "${PREFIX}/lib/libLLVMCore.a" ]] || return 1
	[[ -f "${PREFIX}/lib/libLLVMMCJIT.a" ]] || return 1
	[[ -f "${PREFIX}/lib/libLLVMAArch64CodeGen.a" ]] || return 1
	[[ -f "${PREFIX}/include/llvm/IR/Module.h" ]] || return 1
	return 0
}

if [[ "${FORCE}" != "1" && -f "${STAMP}" ]] && install_present; then
	if [[ "$(stamp_body)" == "$(cat "${STAMP}")" ]]; then
		say "llvm: already built (${LLVM_VERSION}, ${LLVM_TARGETS}, ios ${IOS_DEPLOYMENT_TARGET}) — nothing to do."
		say "      prefix: ${PREFIX}"
		exit 0
	fi
	say "llvm: stamp differs from the requested build — rebuilding."
fi

# Only after the stamp has decided to rebuild, so a matching stamp still exits in about a second.
llvm_patched_source "${PATCHED_SRC_ROOT}"

# Stage 1: host tools only, restricted to the same single target as stage 2 so it configures little backend machinery.

HOST_CONFIGURE_ARGS=(
	-G Ninja
	-DCMAKE_BUILD_TYPE=Release
	"-DLLVM_TARGETS_TO_BUILD=${LLVM_TARGETS}"
	-DLLVM_ENABLE_PROJECTS=
	-DLLVM_ENABLE_RUNTIMES=
	-DLLVM_ENABLE_ZSTD=OFF
	-DLLVM_INCLUDE_TESTS=OFF
	-DLLVM_INCLUDE_EXAMPLES=OFF
	-DLLVM_INCLUDE_BENCHMARKS=OFF
	-DLLVM_INCLUDE_DOCS=OFF
	-DLLVM_ENABLE_WARNINGS=OFF
)

say "llvm: stage 1 — configuring the host tablegen bootstrap (${LLVM_VERSION})"
# Stage 1 stays on the pristine submodule: the tablegens never compile lib/Target, and moving -S would make CMake refuse the existing host-build cache.
"${CMAKE}" -S "${LLVM_SRC}" -B "${HOST_BUILD_DIR}" "${HOST_CONFIGURE_ARGS[@]}"

# llvm-min-tblgen is an LLVM 18+ split, so build it when the source declares it.
HOST_TARGETS=(llvm-tblgen)
if grep -q 'add_tablegen(llvm-min-tblgen' "${LLVM_SRC}/utils/TableGen/CMakeLists.txt"; then
	HOST_TARGETS+=(llvm-min-tblgen)
fi

say "llvm: stage 1 — building ${HOST_TARGETS[*]} with -j${LLVM_JOBS}"
"${CMAKE}" --build "${HOST_BUILD_DIR}" -j "${LLVM_JOBS}" --target "${HOST_TARGETS[@]}"

mkdir -p "${HOST_BIN}"
for target in "${HOST_TARGETS[@]}"; do
	built="${HOST_BUILD_DIR}/bin/${target}"
	[[ -x "${built}" ]] || die "stage 1 did not produce an executable ${built}."

	# It must run here: a tablegen the host cannot run is the failure the two-stage shape exists to avoid.
	"${built}" --version >/dev/null 2>&1 || die \
		"${built} exists but does not run on this host. That is the exact failure the host bootstrap exists to prevent; stage 2 would then either fail or, worse, be handed a cross-built binary."

	cp -f "${built}" "${HOST_BIN}/${target}"
done

say "llvm: stage 1 — host tools ready in ${HOST_BIN}"

# A stale tree from another SDK, target or target list gives an install that does not match its stamp.
rm -rf "${IOS_BUILD_DIR}"
rm -rf "${PREFIX}"

say "llvm: stage 2 — configuring arm64-ios (${LLVM_TARGETS} only, min ${IOS_DEPLOYMENT_TARGET}, SDK ${SDK_VERSION})"
"${CMAKE}" -S "${LLVM_PATCHED_SRC}" -B "${IOS_BUILD_DIR}" "${IOS_CONFIGURE_ARGS[@]}"

say "llvm: stage 2 — building with -j${LLVM_JOBS} (this is the long one)"
"${CMAKE}" --build "${IOS_BUILD_DIR}" -j "${LLVM_JOBS}"

say "llvm: stage 2 — installing to ${PREFIX}"
"${CMAKE}" --install "${IOS_BUILD_DIR}"

# Probe the archives instead of trusting the toolchain settings.

[[ -f "${PREFIX}/lib/cmake/llvm/LLVMConfig.cmake" ]] || die \
	"${PREFIX}/lib/cmake/llvm/LLVMConfig.cmake is missing after install. That file IS the artifact ios/cmake/IosLlvm.cmake points LLVM_DIR at, and without it upstream's find_package(LLVM CONFIG) branch has nothing to resolve."

grep -q 'llvm-src-patched/llvm/lib/Target/AArch64/AArch64FrameLowering.cpp' "${IOS_BUILD_DIR}/build.ninja" || die \
	"${IOS_BUILD_DIR}/build.ninja does not name the patched AArch64FrameLowering.cpp, so stage 2 did not compile the GHC emergency-spill patch."

shopt -s nullglob
ARCHIVES=("${PREFIX}"/lib/*.a)
shopt -u nullglob

[[ ${#ARCHIVES[@]} -gt 0 ]] || die "no archives were installed under ${PREFIX}/lib."

for archive in "${ARCHIVES[@]}"; do
	if ! lipo -info "${archive}" 2>/dev/null | grep -q 'arm64'; then
		die "$(basename "${archive}") is not arm64: $(lipo -info "${archive}" 2>&1)"
	fi
done

say "llvm: ${#ARCHIVES[@]} archive(s) report arm64"

# vtool needs a Mach-O, so probe one extracted member; lipo alone would pass a macOS arm64 archive.
PROBE_DIR="${IOS_BUILD_DIR}/.platform-probe"
mkdir -p "${PROBE_DIR}"

for name in libLLVMCore.a libLLVMAArch64CodeGen.a libLLVMMCJIT.a; do
	path="${PREFIX}/lib/${name}"
	[[ -f "${path}" ]] || die "expected archive missing after install: ${path}"

	member="$(ar -t "${path}" | grep -m1 '\.o$' || true)"
	[[ -n "${member}" ]] || die "${name} contains no object member to probe: $(ar -t "${path}" | head -5)"

	probe="${PROBE_DIR}/probe.o"
	ar p "${path}" "${member}" > "${probe}"

	if ! vtool -show "${probe}" 2>/dev/null | grep -qi 'platform IOS'; then
		die "${name} (member ${member}) does not carry an iOS platform load command. vtool says: $(vtool -show "${probe}" 2>&1 | head -20)"
	fi
	rm -f "${probe}"
done

rm -rf "${PROBE_DIR}"

# Check the silent-X86 case against the install, not the flag: a library list cannot lie about what was built.
X86_COUNT="$(find "${PREFIX}/lib" -maxdepth 1 -name 'libLLVMX86*.a' | wc -l | tr -d ' ')"
[[ "${X86_COUNT}" == "0" ]] || die \
	"the install contains ${X86_COUNT} X86 target librar(y|ies), so LLVM_TARGETS_TO_BUILD did not take effect. This is the exact silent-wrong-answer this build exists to close."

AARCH64_COUNT="$(find "${PREFIX}/lib" -maxdepth 1 -name 'libLLVMAArch64*.a' | wc -l | tr -d ' ')"
[[ "${AARCH64_COUNT}" -ge 1 ]] || die \
	"the install contains no AArch64 target library, so the AArch64 backend was not built at all."

# A target-side llvm-tblgen is built into the stage-2 tree and must not reach the install, since the host cannot run it.
if [[ -d "${PREFIX}/bin" ]]; then
	STRAY="$(find "${PREFIX}/bin" -type f -perm -111 | head -5)"
	[[ -z "${STRAY}" ]] || die \
		"${PREFIX}/bin contains target executables, so LLVM_BUILD_UTILS/LLVM_BUILD_TOOLS did not take effect: ${STRAY}"
fi

[[ -f "${PREFIX}/lib/libLLVMMCJIT.a" ]] || die \
	"libLLVMMCJIT.a is missing. Utilities/JITLLVM.cpp constructs its engine with the MCJIT engine kind and calls LLVMLinkInMCJIT(), and 3rdparty/llvm/CMakeLists.txt requests MCJIT by name, so an install without it cannot link the recompiler."

stamp_body > "${STAMP}"

say "llvm: ready — ${LLVM_VERSION} arm64-ios, targets ${LLVM_TARGETS}"
say "  prefix   : ${PREFIX}"
say "  LLVM_DIR : ${PREFIX}/lib/cmake/llvm"
say "  host bin : ${HOST_BIN}"
say "  X86 libs : ${X86_COUNT}    AArch64 libs: ${AARCH64_COUNT}"
say "  stamp    : ${STAMP}"
