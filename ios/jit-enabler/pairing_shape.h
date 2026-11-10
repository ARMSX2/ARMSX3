// SPDX-License-Identifier: GPL-2.0-only
// The RPPairing shape check the app's PairingFileCheck also makes, so pre-main skips a lockdown file instead of sending it.

#pragma once

#include <CoreFoundation/CoreFoundation.h>
#include <stdbool.h>
#include <stddef.h>
#include <string.h>

#define ARMSX3_PAIRING_MAX_BYTES 65536

typedef enum {
    ARMSX3_PAIRING_INVALID = 0,
    ARMSX3_PAIRING_RP,
    ARMSX3_PAIRING_RP_LOCKDOWN,
    ARMSX3_PAIRING_LOCKDOWN,
} armsx3_pairing_shape;

static inline CFTypeRef armsx3_pairing_value(CFDictionaryRef dictionary, const char* key)
{
    CFStringRef name = CFStringCreateWithCString(kCFAllocatorDefault, key, kCFStringEncodingUTF8);
    if (name == NULL) return NULL;
    CFTypeRef value = CFDictionaryGetValue(dictionary, name);
    CFRelease(name);
    return value;
}

static inline bool armsx3_pairing_is_key(CFTypeRef value)
{
    return value != NULL && CFGetTypeID(value) == CFDataGetTypeID() && CFDataGetLength((CFDataRef)value) == 32;
}

static inline armsx3_pairing_shape armsx3_pairing_shape_of(const void* bytes, size_t length)
{
    if (bytes == NULL || length == 0 || length > ARMSX3_PAIRING_MAX_BYTES) return ARMSX3_PAIRING_INVALID;
    CFDataRef data = CFDataCreateWithBytesNoCopy(kCFAllocatorDefault, (const UInt8*)bytes, (CFIndex)length, kCFAllocatorNull);
    if (data == NULL) return ARMSX3_PAIRING_INVALID;
    CFPropertyListRef plist = CFPropertyListCreateWithData(kCFAllocatorDefault, data, kCFPropertyListImmutable, NULL, NULL);
    armsx3_pairing_shape shape = ARMSX3_PAIRING_INVALID;
    if (plist != NULL && CFGetTypeID(plist) == CFDictionaryGetTypeID()) {
        CFDictionaryRef dictionary = (CFDictionaryRef)plist;
        static const char* const lockdown_keys[] = {"HostCertificate", "DeviceCertificate", "HostPrivateKey", "SystemBUID"};
        bool lockdown = false;
        for (size_t i = 0; i < sizeof lockdown_keys / sizeof *lockdown_keys; i++) {
            if (armsx3_pairing_value(dictionary, lockdown_keys[i]) != NULL) lockdown = true;
        }
        CFTypeRef identifier = armsx3_pairing_value(dictionary, "identifier");
        if (armsx3_pairing_is_key(armsx3_pairing_value(dictionary, "public_key"))
            && armsx3_pairing_is_key(armsx3_pairing_value(dictionary, "private_key"))
            && identifier != NULL && CFGetTypeID(identifier) == CFStringGetTypeID()) {
            shape = lockdown ? ARMSX3_PAIRING_RP_LOCKDOWN : ARMSX3_PAIRING_RP;
        } else if (lockdown) {
            shape = ARMSX3_PAIRING_LOCKDOWN;
        }
    }
    if (plist != NULL) CFRelease(plist);
    CFRelease(data);
    return shape;
}

// Only a file the device itself refused is worth a second source; a launch or timing failure says nothing about the file.
static inline bool armsx3_pairing_should_retry(const char* helper_word)
{
    return helper_word != NULL
        && (strcmp(helper_word, "pairing-rejected") == 0 || strcmp(helper_word, "tunnel-failed") == 0);
}

static inline const char* armsx3_pairing_result_word(const char* helper_word)
{
    if (armsx3_pairing_should_retry(helper_word)) return "rejected";
    static const char* const not_tried[] = {"", "start-failed", "connect-failed", "connect-timeout", "timeout",
                                            "interrupted", "invalid-request", "pairing-absent"};
    if (helper_word == NULL) return "not-tried";
    for (size_t i = 0; i < sizeof not_tried / sizeof *not_tried; i++) {
        if (strcmp(helper_word, not_tried[i]) == 0) return "not-tried";
    }
    return "accepted";
}
