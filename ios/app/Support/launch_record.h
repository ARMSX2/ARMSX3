// SPDX-License-Identifier: GPL-2.0-only
// The one writer of Documents/armsx3/launch.json. Its fixed key set never holds a path or a device identifier.

#ifndef ARMSX3_LAUNCH_RECORD_H
#define ARMSX3_LAUNCH_RECORD_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// `jit_reason` may be null or empty. Returns 0 or the failing step's errno. Off the main thread: it does file I/O.
int armsx3_launch_record_write(int32_t jit_on, const char* jit_reason);

// Reads of this process (the app, or LiveContainer around it), never the helper's. Only numbers leave them.

// 1 if this process's signed entitlements carry `key`, 0 if not, -1 if csops refused.
int armsx3_entitlement_present(const char* key);

// Either spelling, bare or com.apple.security.
int armsx3_get_task_allow_present(void);

// phys_footprint + limit_bytes_remaining, or -1 when unreadable.
long long armsx3_memory_limit_bytes(void);

// -1 when IOKit or the node cannot be read.
int armsx3_txm_present(void);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* ARMSX3_LAUNCH_RECORD_H */
