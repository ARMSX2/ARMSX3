#!/usr/bin/env bash
#
# Refresh rpcs3/git-version.h from the current HEAD.
#
# cmake generates this header, but only when it CONFIGURES, and build-variants.sh deliberately
# skips reconfiguring an already-correct build dir because re-running cmake regenerates LLVM's
# generated headers and costs a full rebuild. So the version stamp froze at whenever cmake last
# ran, and every build after that reported an old commit.
#
# That is not cosmetic. A tester on 0.9.3 reported a 0.9.1-era commit in their log, which sent
# an investigation looking for a regression in commits their build did not contain. A build that
# misreports itself makes every bug report ambiguous.
#
# Writing the header directly is enough: ninja sees it change and rebuilds only what includes it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/rpcs3/git-version.h"

cd "$ROOT"

# The stamp names a commit, so the build has to BE that commit, submodules included. A
# submodule whose checkout differs from the one this commit records (a '+' in `git submodule
# status`) builds library code the stamp does not describe: switching from a test branch that
# bumped curl, wolfSSL or VMA back to armsx3-ship leaves the newer checkouts in place, and a
# release built then would ship them without anyone choosing to. Refuse instead.
# Output captured and tested, not piped into grep -q, so pipefail cannot invert the check.
if mismatched="$(git submodule status 2>/dev/null | grep '^+' || true)"; [ -n "$mismatched" ]; then
	echo "FAIL: these submodules are not checked out at the commit this tree records:" >&2
	echo "$mismatched" >&2
	echo "Fix: git -C \"$ROOT\" submodule update -- <path>   (or --init for all)" >&2
	exit 1
fi

COUNT="$(git rev-list HEAD --count)"
SHA="$(git rev-parse --short=8 HEAD)"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"

NEW="// This is a generated file.

#define RPCS3_GIT_VERSION \"${COUNT}-${SHA}\"
#define RPCS3_GIT_BRANCH \"${BRANCH}\"
#define RPCS3_GIT_FULL_BRANCH \"local_build\"

// If you don't want this file to update/recompile, change to 1.
#define RPCS3_GIT_VERSION_NO_UPDATE 0"

# Only write when it differs, so an unchanged HEAD does not force a rebuild.
if [ ! -f "$OUT" ] || [ "$(cat "$OUT")" != "$NEW" ]; then
	printf '%s' "$NEW" > "$OUT"
	echo "==> git-version.h: ${COUNT}-${SHA} (${BRANCH})"
else
	echo "==> git-version.h already current: ${COUNT}-${SHA}"
fi
