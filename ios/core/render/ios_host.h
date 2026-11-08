// SPDX-License-Identifier: GPL-2.0-only
// Host bring-up in plain C, apart from rpcs3_ios_abi.h because the bridge also links on macOS, where ios_render does not exist.
#ifndef ARMSX3_IOS_RENDER_IOS_HOST_H
#define ARMSX3_IOS_RENDER_IOS_HOST_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Call before Rpcs3IOS_Initialize: 0 or an errno, and on 0 the product config path, truncated to cap and NUL-terminated.
int32_t armsx3_ios_prepare_host(char* out_config_path, uint32_t cap);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* ARMSX3_IOS_RENDER_IOS_HOST_H */
