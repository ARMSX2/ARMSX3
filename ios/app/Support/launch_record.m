// SPDX-License-Identifier: GPL-2.0-only
// The launch record writer. A reading that could not be taken is -1, never 0, so it is never a measured zero.

#include "launch_record.h"
#include "../../jit-enabler/StikJITExtensionLauncher.h"

#include <CoreFoundation/CoreFoundation.h>
#include <mach/mach.h>

#include <sys/sysctl.h>
#include <sys/proc.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <ctype.h>
#include <dlfcn.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

// csops(2) has no SDK header.
extern int csops(pid_t pid, unsigned int ops, void* useraddr, size_t usersize);

#define ARMSX3_CS_OPS_STATUS 0u
#define ARMSX3_CS_DEBUGGED 0x10000000u
#define ARMSX3_CS_OPS_ENTITLEMENTS_BLOB 7u

// 1 if CS_DEBUGGED is set, 0 if not, -1 if csops refused.
static int cs_debugged_now(void)
{
	unsigned int cs_flags = 0;

	if (csops(getpid(), ARMSX3_CS_OPS_STATUS, &cs_flags, sizeof cs_flags) != 0)
	{
		return -1;
	}

	return (cs_flags & ARMSX3_CS_DEBUGGED) != 0;
}

// The leg scorers compare this as a string against `date -u +%Y-%m-%dT%H:%M:%SZ`.
static void now_utc(char out[32])
{
	const time_t now = time(NULL);
	struct tm tm;

	if (gmtime_r(&now, &tm) == NULL || strftime(out, 32, "%Y-%m-%dT%H:%M:%SZ", &tm) == 0)
	{
		strcpy(out, "unknown");
	}
}

// Both -1 when the read fails.
static void proc_start_and_traced(long long* start_to_now_ms, int* traced)
{
	struct kinfo_proc info;
	size_t len = sizeof info;
	int mib[4] = {CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()};
	struct timeval now;

	*start_to_now_ms = -1;
	*traced = -1;
	memset(&info, 0, sizeof info);

	if (sysctl(mib, 4, &info, &len, NULL, 0) != 0)
	{
		return;
	}

	*traced = (info.kp_proc.p_flag & P_TRACED) != 0 ? 1 : 0;

	if (gettimeofday(&now, NULL) == 0)
	{
		const struct timeval start = info.kp_proc.p_starttime;
		*start_to_now_ms = ((long long)now.tv_sec - start.tv_sec) * 1000 + ((long long)now.tv_usec - start.tv_usec) / 1000;
	}
}

// The CS_GenericBlob is on the stack, so the record and the Device card can read at once.
int armsx3_entitlement_present(const char* key)
{
	unsigned char blob[16384];
	char needle[160];
	unsigned length;

	memset(blob, 0, sizeof blob);

	if (csops(getpid(), ARMSX3_CS_OPS_ENTITLEMENTS_BLOB, blob, sizeof blob) != 0)
	{
		return -1;
	}

	length = ((unsigned)blob[4] << 24) | ((unsigned)blob[5] << 16) | ((unsigned)blob[6] << 8) | (unsigned)blob[7];

	if (length <= 8 || length > sizeof blob)
	{
		return -1;
	}

	snprintf(needle, sizeof needle, "<key>%s</key>", key);
	return memmem(blob + 8, (size_t)length - 8, needle, strlen(needle)) != NULL ? 1 : 0;
}

// Xcode's automatic signing embeds the bare key, the manual route the prefixed one.
int armsx3_get_task_allow_present(void)
{
	const int bare = armsx3_entitlement_present("get-task-allow");

	return bare == 1 ? 1 : armsx3_entitlement_present("com.apple.security.get-task-allow");
}

// An older kernel without limit_bytes_remaining gives the footprint alone.
long long armsx3_memory_limit_bytes(void)
{
	task_vm_info_data_t info;
	mach_msg_type_number_t count = TASK_VM_INFO_COUNT;
	unsigned long long remaining = 0;

	memset(&info, 0, sizeof info);

	if (task_info(mach_task_self(), TASK_VM_INFO, (task_info_t)&info, &count) != KERN_SUCCESS)
	{
		return -1;
	}

	if (count >= TASK_VM_INFO_REV4_COUNT)
	{
		remaining = (unsigned long long)info.limit_bytes_remaining;
	}

	return (long long)((unsigned long long)info.phys_footprint + remaining);
}

// IOKit has no iOS SDK header, so its calls are resolved by name; any failure is -1, an honest Unknown.
int armsx3_txm_present(void)
{
	typedef mach_port_t (*from_path_fn)(mach_port_t, const char*);
	typedef CFTypeRef (*property_fn)(mach_port_t, CFStringRef, CFAllocatorRef, uint32_t);
	typedef kern_return_t (*release_fn)(mach_port_t);
	void* iokit = dlopen("/System/Library/Frameworks/IOKit.framework/IOKit", RTLD_LAZY);
	from_path_fn from_path;
	property_fn property;
	release_fn release;
	mach_port_t entry;
	CFTypeRef keys;
	int present = -1;

	if (iokit == NULL)
	{
		return -1;
	}

	from_path = (from_path_fn)dlsym(iokit, "IORegistryEntryFromPath");
	property = (property_fn)dlsym(iokit, "IORegistryEntryCreateCFProperty");
	release = (release_fn)dlsym(iokit, "IOObjectRelease");

	if (from_path == NULL || property == NULL || release == NULL)
	{
		return -1;
	}

	// MACH_PORT_NULL is kIOMainPortDefault.
	entry = from_path(MACH_PORT_NULL, "IODeviceTree:/chosen/memory-map");
	if (entry == MACH_PORT_NULL)
	{
		return -1;
	}

	keys = property(entry, CFSTR("IORegistryEntryPropertyKeys"), kCFAllocatorDefault, 0);
	(void)release(entry);

	if (keys != NULL && CFGetTypeID(keys) == CFArrayGetTypeID())
	{
		const CFArrayRef array = (CFArrayRef)keys;
		present = CFArrayContainsValue(array, CFRangeMake(0, CFArrayGetCount(array)), CFSTR("TXM")) ? 1 : 0;
	}

	if (keys != NULL)
	{
		CFRelease(keys);
	}

	// The handle stays open: IOKit is already in every UIKit process.
	return present;
}

// An empty or null source becomes "none".
static void sanitise(char* out, size_t n, const char* src, const char* extra)
{
	size_t i = 0;

	if (src == NULL || src[0] == '\0')
	{
		src = "none";
	}

	for (; src[i] != '\0' && i + 1 < n; ++i)
	{
		const unsigned char c = (unsigned char)src[i];
		const int ok = islower(c) || isdigit(c) || c == '-' || (extra != NULL && c != '\0' && strchr(extra, c) != NULL);
		out[i] = ok ? (char)c : '?';
	}

	out[i] = '\0';
}

static void sysctl_word(const char* name, char* out, size_t n)
{
	char raw[64] = {0};
	size_t len = sizeof raw - 1;

	if (sysctlbyname(name, raw, &len, NULL, 0) != 0)
	{
		raw[0] = '\0';
	}

	// Upper case, ',' and '.' are real content in hw.machine and kern.osversion.
	sanitise(out, n, raw, "ABCDEFGHIJKLMNOPQRSTUVWXYZ,.");
}

int armsx3_launch_record_write(int32_t jit_on, const char* jit_reason)
{
	const char* home = getenv("HOME");
	char dir[1024];
	char current[1100];
	char previous[1100];
	char partial[1100];
	char utc[32];
	char machine[64];
	char os_build[64];
	char reason[64];
	char helper[32];
	long long launch_to_ui_ms;
	int traced;
	long long premain_ms = -1;
	armsx3_jit_enabler_result enabler;
	FILE* f;
	int failed;
#if DEBUG
	const char* const configuration = "debug";
#else
	const char* const configuration = "release";
#endif

	if (home == NULL || home[0] == '\0')
	{
		return ENOENT;
	}

	if (snprintf(dir, sizeof dir, "%s/Documents/armsx3", home) >= (int)sizeof dir
		|| snprintf(current, sizeof current, "%s/launch.json", dir) >= (int)sizeof current
		|| snprintf(previous, sizeof previous, "%s/launch-previous.json", dir) >= (int)sizeof previous
		|| snprintf(partial, sizeof partial, "%s/launch.json.partial", dir) >= (int)sizeof partial)
	{
		return ENAMETOOLONG;
	}

	proc_start_and_traced(&launch_to_ui_ms, &traced);
	now_utc(utc);
	sysctl_word("hw.machine", machine, sizeof machine);
	sysctl_word("kern.osversion", os_build, sizeof os_build);
	sanitise(reason, sizeof reason, jit_reason, NULL);

	memset(&enabler, 0, sizeof enabler);
	if (armsx3_jit_enabler_last_result(&enabler) == 0)
	{
		premain_ms = (long long)enabler.premain_ms;
		enabler.helper_outcome[sizeof enabler.helper_outcome - 1] = '\0';
		sanitise(helper, sizeof helper, enabler.helper_outcome, NULL);
	}
	else
	{
		sanitise(helper, sizeof helper, NULL, NULL);
	}

	if (mkdir(dir, 0755) != 0 && errno != EEXIST)
	{
		return errno;
	}

	// shortcut: two files, current and previous, and nothing older.
	if (rename(current, previous) != 0 && errno != ENOENT)
	{
		return errno;
	}

	f = fopen(partial, "w");
	if (f == NULL)
	{
		return errno;
	}

	fprintf(f,
		"{\n"
		"  \"schema\": \"armsx3-launch-record/1\",\n"
		"  \"utc\": \"%s\",\n"
		"  \"configuration\": \"%s\",\n"
		"  \"machine\": \"%s\",\n"
		"  \"os_build\": \"%s\",\n"
		"  \"launch_to_ui_ms\": %lld,\n"
		"  \"premain_ms\": %lld,\n"
		"  \"helper_outcome\": \"%s\",\n"
		"  \"jit_state\": \"%s\",\n"
		"  \"jit_reason\": \"%s\",\n"
		"  \"cs_debugged\": %d,\n"
		"  \"p_traced\": %d,\n"
		"  \"ent_get_task_allow\": %d,\n"
		"  \"ent_increased_memory_limit\": %d,\n"
		"  \"jetsam_limit_bytes\": %lld\n"
		"}\n",
		utc, configuration, machine, os_build, launch_to_ui_ms, premain_ms, helper, jit_on ? "on" : "off", reason,
		cs_debugged_now(), traced, armsx3_get_task_allow_present(),
		armsx3_entitlement_present("com.apple.developer.kernel.increased-memory-limit"), armsx3_memory_limit_bytes());

	failed = ferror(f);
	if (fclose(f) != 0 || failed)
	{
		const int err = errno != 0 ? errno : EIO;
		unlink(partial);
		return err;
	}

	if (rename(partial, current) != 0)
	{
		return errno;
	}

	return 0;
}
