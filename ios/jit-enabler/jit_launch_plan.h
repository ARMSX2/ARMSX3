// SPDX-License-Identifier: GPL-2.0-only
// Which JIT method the player stored, and whether LiveContainer hosts the process; anything but exactly stikdebug or wait is Built-in.
#pragma once

#include <string.h>

#define ARMSX3_JIT_METHOD_KEY "armsx3.settings.jitMethod"
#define ARMSX3_JIT_METHOD_BUILTIN "builtin"
#define ARMSX3_JIT_METHOD_STIKDEBUG "stikdebug"
#define ARMSX3_JIT_METHOD_WAIT "wait"

enum
{
	ARMSX3_JIT_PLAN_BUILTIN = 0,
	ARMSX3_JIT_PLAN_STIKDEBUG = 1,
	ARMSX3_JIT_PLAN_WAIT = 2,
	ARMSX3_JIT_PLAN_METHOD_MASK = 3,
	ARMSX3_JIT_PLAN_LIVECONTAINER = 4,
};

// The method code in the low bits, plus the LiveContainer bit. Pure, so the host test compiles it.
static inline int armsx3_jit_plan_compose(int livecontainer, const char* method)
{
	int plan = ARMSX3_JIT_PLAN_BUILTIN;

	if (method != NULL && strcmp(method, ARMSX3_JIT_METHOD_STIKDEBUG) == 0)
	{
		plan = ARMSX3_JIT_PLAN_STIKDEBUG;
	}
	else if (method != NULL && strcmp(method, ARMSX3_JIT_METHOD_WAIT) == 0)
	{
		plan = ARMSX3_JIT_PLAN_WAIT;
	}

	return livecontainer ? (plan | ARMSX3_JIT_PLAN_LIVECONTAINER) : plan;
}

#ifdef __cplusplus
extern "C" {
#endif

// Defined in StikJITExtensionLauncher.m; it only reads, and launches nothing.
int armsx3_jit_launch_plan(void);

#ifdef __cplusplus
}
#endif
