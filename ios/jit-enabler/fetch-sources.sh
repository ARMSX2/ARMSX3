#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-only
# Fetches the INCLUDE rows of ios/jit-enabler/vendor.tsv at their pinned commits into the gitignored ios/3rdparty/jit-enabler/, aborting on a blob SHA mismatch.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT}"

MANIFEST="ios/jit-enabler/vendor.tsv"
DEST="ios/3rdparty/jit-enabler"

if [[ ! -s "${MANIFEST}" ]]; then
	echo "fetch-sources.sh: ${MANIFEST} is missing or empty; there is nothing pinned to fetch." >&2
	exit 2
fi

if ! command -v gh >/dev/null 2>&1; then
	echo "fetch-sources.sh: gh is not on PATH." >&2
	exit 2
fi

if ! git check-ignore -q "${DEST}/probe"; then
	echo "fetch-sources.sh: refusing: ${DEST}/ is not gitignored, so a fetched file could be staged." >&2
	exit 2
fi

rows="$(grep -v '^#' "${MANIFEST}" || true)"
if [[ -z "${rows}" ]]; then
	echo "fetch-sources.sh: ${MANIFEST} holds no rows." >&2
	exit 2
fi

fetched=0
while IFS=$'\t' read -r file upstream pin blob _spdx verdict; do
	[[ "${verdict}" == "INCLUDE" ]] || continue

	if [[ ! "${pin}" =~ ^[0-9a-f]{40}$ || ! "${blob}" =~ ^[0-9a-f]{40}$ ]]; then
		echo "fetch-sources.sh: INCLUDE row ${file} has no full pin or blob SHA; refusing." >&2
		exit 1
	fi
	if [[ ! "${upstream}" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]]; then
		echo "fetch-sources.sh: INCLUDE row ${file} names a malformed upstream ${upstream}; refusing." >&2
		exit 1
	fi
	if [[ "${file}" == /* || "${file}" == *..* || "${file}" == *" "* ]]; then
		echo "fetch-sources.sh: INCLUDE row names an unsafe path ${file}; refusing." >&2
		exit 1
	fi
	# Belt and braces over the Verdict column: no script, archive, framework or Manic path is fetched, even if a row were mislabelled INCLUDE.
	case "${file}" in
		*.js | *.a | *.xcframework | *.xcframework/* | *Manic*)
			echo "fetch-sources.sh: ${file} is an excluded kind of file; refusing." >&2
			exit 1
			;;
	esac

	out="${DEST}/${upstream}/${file}"
	mkdir -p "$(dirname "${out}")"
	part="${out}.part"
	gh api -H "Accept: application/vnd.github.raw" "repos/${upstream}/contents/${file}?ref=${pin}" < /dev/null > "${part}"

	got="$(git hash-object "${part}")"
	if [[ "${got}" != "${blob}" ]]; then
		rm -f "${part}"
		echo "fetch-sources.sh: BLOB MISMATCH for ${upstream} ${file} at ${pin}: got ${got}, pinned ${blob}. HALT." >&2
		exit 1
	fi
	mv "${part}" "${out}"
	echo "verified ${blob} ${upstream} ${file}"
	fetched=$((fetched + 1))
done <<< "${rows}"

if [[ "${fetched}" -eq 0 ]]; then
	echo "fetch-sources.sh: ${MANIFEST} has no INCLUDE row; nothing fetched." >&2
	exit 1
fi

echo "fetch-sources.sh: fetched and verified ${fetched} INCLUDE file(s) into ${DEST}/"
