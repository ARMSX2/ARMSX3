// SPDX-License-Identifier: GPL-2.0-only
// Reads LiveContainer's private state read-only and behind checks, and reports unknown rather than guessing.

import Foundation
import ObjectiveC
import Darwin

enum LiveContainerProbe {
    // Read once, since none of it changes after launch. Settable so a smoke scene can draw LiveContainer states.
    @MainActor static var current: JitFallback.Probe = read()

    static func read() -> JitFallback.Probe {
        guard getenv("LC_HOME_PATH") != nil else { return JitFallback.Probe(insideLiveContainer: false) }
        if getenv("LP_HOME_PATH") != nil { return JitFallback.Probe(insideLiveContainer: true, multitask: true) }

        var probe = JitFallback.Probe(insideLiveContainer: true)
        probe.hooksActive = hooksActive()
        probe.helperSettingStored = helperSettingStored()
        if let bundle = liveContainerBundle() {
            probe.version = bundle.infoDictionary?["CFBundleShortVersionString"] as? String
            probe.bundleID = bundle.bundleIdentifier
            let extensionURL = bundle.builtInPlugInsURL?.appendingPathComponent("LiveProcess.appex")
            probe.liveProcessPresent = extensionURL.flatMap { Bundle(url: $0) }?.bundleIdentifier != nil
        }
        return probe
    }

    @MainActor static var stikDebugBundleID: String {
        current.bundleID ?? Bundle.main.bundleIdentifier ?? ""
    }

    /// LiveContainer clears its selection before it runs a guest, so a StikDebug relaunch would land in its app list.
    static func selectForRelaunch() {
        let lcDefaults = NSSelectorFromString("lcUserDefaults")
        guard (UserDefaults.self as AnyObject).responds(to: lcDefaults),
              let defaults = (UserDefaults.self as AnyObject).perform(lcDefaults)?.takeUnretainedValue() as? UserDefaults,
              let home = ProcessInfo.processInfo.environment["HOME"] else { return }
        defaults.set((Bundle.main.bundlePath as NSString).lastPathComponent, forKey: "selected")
        defaults.set((home as NSString).lastPathComponent, forKey: "selectedContainer")
    }

    private static func liveContainerBundle() -> Bundle? {
        let mainBundle = NSSelectorFromString("lcMainBundle")
        guard (UserDefaults.self as AnyObject).responds(to: mainBundle) else { return nil }
        return (UserDefaults.self as AnyObject).perform(mainBundle)?.takeUnretainedValue() as? Bundle
    }

    // LiveContainer swaps the method's implementation for its own, so the implementation sits in another image than the class.
    private static func hooksActive() -> Bool? {
        guard let extensionClass = NSClassFromString("NSExtension") else { return nil }
        let lookup = NSSelectorFromString("extensionWithIdentifier:excludingDisabledExtensions:error:")
        guard (extensionClass as AnyObject).responds(to: lookup),
              let method = class_getClassMethod(extensionClass, lookup) else { return nil }
        var implementationInfo = Dl_info()
        var classInfo = Dl_info()
        let implementation = unsafeBitCast(method_getImplementation(method), to: UnsafeRawPointer.self)
        let classObject = UnsafeRawPointer(Unmanaged.passUnretained(extensionClass as AnyObject).toOpaque())
        guard dladdr(implementation, &implementationInfo) != 0, dladdr(classObject, &classInfo) != 0 else { return nil }
        // The base address only: with hiding on, LiveContainer renames the image paths.
        return implementationInfo.dli_fbase != classInfo.dli_fbase
    }

    private static func helperSettingStored() -> Bool? {
        let info = NSDictionary(contentsOfFile: Bundle.main.bundlePath + "/LCAppInfo.plist")
        return info?["helperProcessesSupport"] as? Bool
    }
}
