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
