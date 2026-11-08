#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-only
# Usage: ios/scripts/bootstrap-submodules.sh [--with-llvm] [--list] [path...]
# Initialises only the submodules the iOS build needs, from the list below; re-runs do nothing.
# --with-llvm adds the LLVM checkout ios-llvm needs; --list prints the selection and changes nothing.
set -euo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_DIR="$(cd "${SCRIPTS_DIR}/.." && pwd)"
REPO_DIR="$(cd "${IOS_DIR}/.." && pwd)"

# Every submodule in .gitmodules, in one section each: <path> [<marker>] # reason
SUBMODULES=$(cat <<'EOF'
[ALLOW]
3rdparty/7zip/7zip                        # the Loader reads PKG and PUP archives through it
3rdparty/GPUOpen/VulkanMemoryAllocator    # header-only, on rpcs3_emu's link line
3rdparty/OpenAL/openal-soft               # built on iOS; cellMic.h includes alc.h
3rdparty/SoundTouch/soundtouch            # audio time-stretch, on rpcs3_emu's link line
3rdparty/asmjit/asmjit                    # the SPU ASMJIT recompiler
3rdparty/cubeb/cubeb                      # audio output; ios/CMakeLists.txt patches a copy of its AudioUnit backend for iOS
3rdparty/curl/curl                        # built from source so the host curl never enters an iOS link
3rdparty/fusion/fusion                    # the PS Move calibration and tracker sources link it
3rdparty/glslang/glslang                  # compiles PS3 shaders to SPIR-V
3rdparty/libchdr                          # CHD disc images
3rdparty/libpng/libpng                    # on rpcs3_emu's link line
3rdparty/llvm/llvm  <gated:with-llvm>     # large checkout only ios-llvm needs
3rdparty/miniupnp/miniupnp                # port mapping for the network layer
3rdparty/protobuf/protobuf                # the NP gencode asserts this exact version
3rdparty/pugixml                          # on rpcs3_emu's link line
3rdparty/rtmidi/rtmidi                    # on rpcs3_emu's link line
3rdparty/stblib/stb                       # include path only; absent means an unresolved include
3rdparty/wolfssl/wolfssl                  # TLS; curl is built against it
3rdparty/yaml-cpp/yaml-cpp                # every configuration file is YAML
3rdparty/zlib/zlib                        # built from source so the host zlib never enters an iOS link
3rdparty/zstd/zstd                        # on rpcs3_emu's link line

[NEVER]
3rdparty/hidapi/hidapi                    # the Darwin backend needs IOKit HID headers the iOS SDK lacks
3rdparty/libusb/libusb                    # its os.cmake refuses the iOS platform at configure time
3rdparty/ffmpeg                           # prebuilt binaries with no iOS slice; FFmpeg is built from source
3rdparty/libsdl-org/SDL                   # USE_SDL is off for iOS
3rdparty/opencv/opencv                    # find_package(OpenCV) is meant to fail so tracking reports unsupported

[NOT-NEEDED]
3rdparty/oboe/oboe                        # Android audio backend
3rdparty/spirv-cross/SPIRV-Cross          # Android's OpenGL renderer
3rdparty/discord-rpc/discord-rpc          # USE_DISCORD_RPC is off
3rdparty/FAudio                           # USE_FAUDIO is off
3rdparty/feralinteractive/feralinteractive  # GameMode is Linux-only
EOF
)

GIT_JOBS="${GIT_JOBS:-8}"

usage()
{
	# The header comment is the usage text.
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

WITH_LLVM=0
LIST_ONLY=0
REQUESTED=()

while [[ $# -gt 0 ]]; do
	case "$1" in
		--with-llvm)
			WITH_LLVM=1
			shift
			;;
		--list)
			LIST_ONLY=1
			shift
			;;
		--help|-h)
			usage
			exit 0
			;;
		--*)
			usage >&2
			die "unknown option: $1"
			;;
		*)
			REQUESTED+=("$1")
			shift
			;;
	esac
done

# One tab-separated record per entry (section, path, markers, reason); everything downstream reads this, so the parse exists once.

entries()
{
	awk '
		{ sub(/\r$/, "") }
		/^[[:space:]]*$/ { next }
		/^[[:space:]]*#/ { next }
		/^\[[A-Z-]+\][[:space:]]*$/ {
			section = substr($0, 2, index($0, "]") - 2)
			next
		}
		{
			reason = ""
			body = $0
			hash = index($0, "#")
			if (hash > 0) {
				reason = substr($0, hash + 1)
				body = substr($0, 1, hash - 1)
				gsub(/^[[:space:]]+|[[:space:]]+$/, "", reason)
			}
			n = split(body, field, /[[:space:]]+/)
			path = ""
			markers = ""
			for (i = 1; i <= n; i++) {
				if (field[i] == "") continue
				if (path == "") { path = field[i]; continue }
				markers = markers (markers == "" ? "" : ",") field[i]
			}
			if (path != "") printf "%s\t%s\t%s\t%s\n", section, path, markers, reason
		}
	' <<< "${SUBMODULES}"
}

# Path lookup by section. Prints one path per line.
paths_in_section()
{
	entries | awk -F'\t' -v want="$1" '$1 == want { print $2 }'
}

markers_for()
{
	entries | awk -F'\t' -v want="$1" '$2 == want { print $3; exit }'
}

section_of()
{
	entries | awk -F'\t' -v want="$1" '$2 == want { print $1; exit }'
}

reason_for()
{
	entries | awk -F'\t' -v want="$1" '$2 == want { print $4; exit }'
}

has_marker()
{
	case ",$(markers_for "$1")," in
		*",$2,"*) return 0 ;;
		*) return 1 ;;
	esac
}

SELECTED=()

if [[ ${#REQUESTED[@]} -gt 0 ]]; then
	for path in "${REQUESTED[@]}"; do
		section="$(section_of "${path}")"

		case "${section}" in
			ALLOW)
				;;
			"")
				die "'${path}' is not in this script's list. Every submodule this build initialises is named in it with a reason; add it deliberately or do not initialise it."
				;;
			*)
				# The never-fetch rule, with the recorded reason so the refusal explains itself.
				die "refusing to initialise '${path}': it is in the [${section}] section of this script's list. Recorded reason: ${section} — $(reason_for "${path}")"
				;;
		esac

		if has_marker "${path}" "<gated:with-llvm>" && [[ "${WITH_LLVM}" != "1" ]]; then
			die "'${path}' is gated on --with-llvm. Pass it explicitly if that is what you meant."
		fi

		SELECTED+=("${path}")
	done
else
	while IFS= read -r path; do
		[[ -n "${path}" ]] || continue

		if has_marker "${path}" "<gated:with-llvm>" && [[ "${WITH_LLVM}" != "1" ]]; then
			continue
		fi

		SELECTED+=("${path}")
	done < <(paths_in_section ALLOW)
fi

[[ ${#SELECTED[@]} -gt 0 ]] || die "nothing selected — the list has no [ALLOW] entries this invocation would initialise."

if [[ "${LIST_ONLY}" == "1" ]]; then
	printf '%s\n' "${SELECTED[@]}"
	exit 0
fi

# Only paths git reports as uninitialised go to git, so a re-run does nothing instead of re-running its fetch machinery.

TO_INIT=()

for path in "${SELECTED[@]}"; do
	status="$(git -C "${REPO_DIR}" submodule status -- "${path}" 2>/dev/null || true)"

	[[ -n "${status}" ]] || die "git does not know a submodule at '${path}'. The list and .gitmodules disagree."

	if [[ "${status:0:1}" == "-" ]]; then
		TO_INIT+=("${path}")
	fi
done

say "selected : ${#SELECTED[@]} path(s)"

if [[ ${#TO_INIT[@]} -eq 0 ]]; then
	say "already  : every selected path is initialised — nothing to do"
	exit 0
fi

say "init     : ${#TO_INIT[@]} path(s): ${TO_INIT[*]}"

# --depth=1 because nothing here needs the history; the superproject pin identifies the revision.
git -C "${REPO_DIR}" submodule update --init --depth=1 --jobs="${GIT_JOBS}" -- "${TO_INIT[@]}"

say "done"
