#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-only
# Runs every ios/tests/test_*.py, prints one result line per file, and exits non-zero if any failed.
set -euo pipefail

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${TESTS_DIR}/../.." && pwd)"
PYTHON="${PYTHON:-python3}"

cd "${ROOT_DIR}"

shopt -s nullglob
tests=("${TESTS_DIR}"/test_*.py)
shopt -u nullglob

if [[ ${#tests[@]} -eq 0 ]]; then
	echo "no tests found in ${TESTS_DIR} (expected ios/tests/test_*.py)"
	exit 0
fi

failed=()

# ARMSX3_TEST_JOBS files at a time (default 4); results are read back in glob order, as a one-by-one run prints them.
JOBS="${ARMSX3_TEST_JOBS:-4}"
OUT_DIR="$(mktemp -d)"
trap 'rm -rf "${OUT_DIR}"' EXIT
export PYTHON OUT_DIR
printf '%s\0' "${tests[@]}" | xargs -0 -n 1 -P "${JOBS}" bash -c '
	name="$(basename "$1")"
	start=${SECONDS}
	"${PYTHON}" "$1" > "${OUT_DIR}/${name}.out" 2>&1 && rc=0 || rc=$?
	echo "${rc}" > "${OUT_DIR}/${name}.rc"
	echo $(( SECONDS - start )) > "${OUT_DIR}/${name}.sec"
' _

# Totals of what the test files themselves reported; the runner never counts cases of its own.
cases_run=0
cases_skipped=0

for test_path in "${tests[@]}"; do
	name="$(basename "${test_path}")"
	# A missing .rc means the file never finished (its runner was killed); count it as a failure.
	output="$(cat "${OUT_DIR}/${name}.out" 2>/dev/null || true)"
	status="$(cat "${OUT_DIR}/${name}.rc" 2>/dev/null || echo 1)"

	if [[ ${status} -eq 0 ]]; then
		# Keep `|| true`: under pipefail a grep with no match would end the whole run.
		summary="$(printf '%s\n' "${output}" | { grep -E '^(Ran |OK|NO TESTS RAN)' || true; } | tr '\n' ' ' | sed 's/[[:space:]]*$//')"

		if [[ -n "${summary}" ]]; then
			echo "PASS  ${name}  ${summary}"
		else
			echo "PASS  ${name}"
		fi

		# awk exits 0 on no match, so an empty extraction adds zero.
		file_ran="$(printf '%s\n' "${summary}" | awk '{ for (i = 1; i <= NF; i++) if ($i == "Ran" && (i + 1) <= NF) s += $(i + 1) } END { print s + 0 }')"
		file_skipped="$(printf '%s\n' "${summary}" | awk '{ rest = $0; while (match(rest, /skipped=[0-9]+/)) { s += substr(rest, RSTART + 8, RLENGTH - 8); rest = substr(rest, RSTART + RLENGTH) } } END { print s + 0 }')"
		cases_run=$(( cases_run + file_ran ))
		cases_skipped=$(( cases_skipped + file_skipped ))
	else
		echo "FAIL  ${name}"
		printf '%s\n' "${output}" | sed 's/^/      /'
		failed+=("${name}")
	fi
done

echo ""
echo "Slowest files (seconds):"
for test_path in "${tests[@]}"; do
	name="$(basename "${test_path}")"
	printf '%s %s\n' "$(cat "${OUT_DIR}/${name}.sec" 2>/dev/null || echo 0)" "${name}"
done | sort -rn | head -10 | sed 's/^/  /'
echo ""

if [[ ${#failed[@]} -gt 0 ]]; then
	echo "${#failed[@]} of ${#tests[@]} test file(s) failed:"
	for name in "${failed[@]}"; do
		echo "  ${name}"
	done
	exit 1
fi

echo "${#tests[@]} test file(s) passed; ${cases_run} case(s) run, ${cases_skipped} skipped."
