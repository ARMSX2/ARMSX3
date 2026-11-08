// Copyright 2025 DolphiniOS Project
// SPDX-License-Identifier: GPL-2.0-or-later

// Adapted from DolphiniOS pull request #277: a no-op XPC class check lets the host's listener endpoint decode.

#import <Foundation/Foundation.h>
#import <dlfcn.h>
#import <objc/runtime.h>

static void Armsx3JITEnablerAllowXPCClass(id self, SEL _cmd, id cls, id key, BOOL allowingInvocations) {}

__attribute__((used, visibility("default")))
int NSExtensionMain(int argc, char* argv[]) {
    NSLog(@"[JITEnabler] NSExtensionMain entered");

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wundeclared-selector"
    Method method = class_getInstanceMethod(
        NSClassFromString(@"NSXPCDecoder"),
        @selector(_validateAllowedClass:forKey:allowingInvocations:));
    if (method) {
        method_setImplementation(method, (IMP)Armsx3JITEnablerAllowXPCClass);
    }
#pragma clang diagnostic pop

    int (*originalNSExtensionMain)(int, char**) =
        (int (*)(int, char**))dlsym(RTLD_NEXT, "NSExtensionMain");
    return originalNSExtensionMain(argc, argv);
}
