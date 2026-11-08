// SPDX-License-Identifier: GPL-2.0-only
// Stderr writes that never block: a full pipe drops and counts lines instead of stopping a thread.
#pragma once

#include <stddef.h>
#include <stdint.h>

#if defined(__GNUC__) || defined(__clang__)
#define ARMSX3_WITNESS_PRINTF(a, b) __attribute__((format(printf, a, b)))
#else
#define ARMSX3_WITNESS_PRINTF(a, b)
#endif

#ifdef __cplusplus
extern "C" {
#endif

void armsx3_stderr_witness(const char* data, size_t len);
void armsx3_stderr_witness_printf(const char* fmt, ...) ARMSX3_WITNESS_PRINTF(1, 2);
uint64_t armsx3_stderr_witness_dropped(void);
// Installs the drain for Swift; safe to call twice.
int armsx3_stderr_witness_install_drain(void); /* 1 if it installed, else 0; int keeps the header free of <stdbool.h> */

#ifdef __cplusplus
}

namespace utils
{
	// Writes `len` bytes to fd 2 if it is writable now, else counts one dropped line.
	void stderr_witness(const char* data, size_t len) noexcept;

	// vsnprintf into a 2048-byte stack buffer (longer text is truncated, keeping a final newline), then stderr_witness.
	void stderr_witness_printf(const char* fmt, ...) noexcept ARMSX3_WITNESS_PRINTF(1, 2);

	// Lines dropped since the process started: the witness's own, plus lines the drain discarded from any writer.
	uint64_t stderr_witness_dropped() noexcept;

	// Apple only: routes fd 2 through a non-blocking pipe this process drains, once per process; starts a thread, so not at static init.
	bool stderr_witness_install_drain() noexcept;
}
#endif
