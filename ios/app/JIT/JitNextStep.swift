// SPDX-License-Identifier: GPL-2.0-only
// The JIT and Device pane's decisions. Foundation only, so the host tests compile it on the Mac.

import Foundation

// The raw values are the pre-main reader's words in jit_launch_plan.h; a host test keeps them equal.
enum JitMethod: String, CaseIterable { case builtin, stikdebug, wait }

extension JitMethod {
    /// Anything but an exact method word is Built-in, as in the pre-main reader.
    static func stored(_ raw: String?) -> JitMethod {
        JitMethod(rawValue: raw ?? "") ?? .builtin
    }

    var title: String {
        switch self {
        case .builtin, .stikdebug: return String(localized: "Built-in")
        case .wait: return String(localized: "Wait for Debugger")
        }
    }

    var caption: String {
        switch self {
        case .builtin, .stikdebug: return String(localized: "ARMSX3 turns on JIT by itself each time it opens. Needs a pairing file and LocalDevVPN.")
        case .wait: return String(localized: "ARMSX3 waits for a debugger you attach yourself, from a Mac.")
        }
    }
}

// No pid is sent: StikDebug relaunches the app traced, and TXM unknown counts as present.
enum StikDebugLink {
    static func url(bundleID: String, txm: Presence) -> URL? {
        var components = URLComponents()
        components.scheme = "stikdebug"
        components.host = "enable-jit"
        var items = [URLQueryItem(name: "bundle-id", value: bundleID)]
        if txm != .missing {
            items.append(URLQueryItem(name: "script-name", value: "universal.js"))
        }
        components.queryItems = items
        return components.url
    }
}

enum DiskImageState: Equatable {
    case mounted, notMounted, unknown, busy(String)

    /// The helper attaches only after its disk image check, so a word from a later step means Mounted.
    static func fromHelperOutcome(_ word: String) -> DiskImageState {
        switch word {
        case "finished", "attach-failed", "prepare-incomplete", "detach-failed": return .mounted
        case "ddi-not-mounted": return .notMounted
        default: return .unknown
        }
    }
}

enum JitPrimary: String {
    case tryAgain, importPairingFile, mountDiskImage, openStikDebug
    case getFromSideStore

    var title: String {
        switch self {
        case .tryAgain: return String(localized: "Try Again")
        case .importPairingFile: return String(localized: "Import Pairing File")
        case .getFromSideStore: return String(localized: "Get from SideStore")
        case .mountDiskImage: return String(localized: "Mount Disk Image")
        case .openStikDebug: return String(localized: "Open StikDebug")
        }
    }
}

// `jitOn` is the process's own launch verdict; an opened StikDebug link never sets it.
struct JitSituation {
    var jitOn = false
    var entitlementMissing = false
    var method = JitMethod.builtin
    var pairingImported = false
    var diskImage = DiskImageState.unknown
    var helperWord = ""
    var stikDebugInstalled = false
    var stikDebugReturnedOff = false
    var txm = Presence.unknown
    var liveContainer = false
    var mountResult: String? = nil
    var actedThisLaunch = false
    var importRefusal: String? = nil
    var sideStoreAvailable = false
    var isPad = false
    var sideStoreSentNothing = false
    var sideStoreLockdown = false
    var checklistFails = false
    var liveProcessMissing = false
    var liveContainerOld = false
    var fallbackAdvice: String? = nil
    var testResult: JitTestResult? = nil
}

// The first stage Test JIT Setup could not pass, from the helper's check-only result word.
enum JitTestResult: Equatable {
    enum Stage: String { case helper, localDevVPN = "LocalDevVPN", pairing, diskImage = "disk image" }

    case passed
    case stopped(Stage, reason: String)

    /// Any word but the four stage words means the helper itself failed.
    init(result: String, reason: String) {
        switch result {
        case "ready": self = .passed
        case "unreachable": self = .stopped(.localDevVPN, reason: reason)
        case "pairing": self = .stopped(.pairing, reason: reason)
        case "unmounted": self = .stopped(.diskImage, reason: reason)
        default: self = .stopped(.helper, reason: reason)
        }
    }

    var stoppedStage: Stage? {
        guard case .stopped(let stage, _) = self else { return nil }
        return stage
    }

    var value: String {
        switch self {
        case .passed: return String(localized: "Passed")
        case .stopped(.helper, _): return String(localized: "Stopped at helper")
        case .stopped(.localDevVPN, _): return String(localized: "Stopped at LocalDevVPN")
        case .stopped(.pairing, _): return String(localized: "Stopped at pairing")
        case .stopped(.diskImage, _): return String(localized: "Stopped at disk image")
        }
    }

    /// `tooOld` is the installed LiveContainer version when it is below what built-in JIT needs.
    func fix(liveContainer: Bool, tooOld version: String? = nil) -> String? {
        guard case .stopped(let stage, _) = self else { return nil }
        switch stage {
        case .helper:
            if let version { return String(localized: "This LiveContainer is \(version), and built-in JIT needs 3.8.13 or later. Update LiveContainer, then install ARMSX3 in it again.") }
            return liveContainer
                ? String(localized: "LiveContainer didn't start the JIT helper. Check that Helper Process Support is on for ARMSX3, then close ARMSX3 and open it again.")
                : String(localized: "The JIT helper didn't start. Close ARMSX3 fully and open it again.")
        case .localDevVPN: return JitNextStep.mountSentence("unreachable")
        case .pairing: return JitNextStep.mountSentence("pairing")
        case .diskImage: return String(localized: "The Developer Disk Image isn't mounted. Mount it, then test again.")
        }
    }

    var passedDetail: String? {
        guard self == .passed else { return nil }
        return String(localized: "Helper started, device reached through LocalDevVPN, pairing accepted, disk image mounted.")
    }

    /// Stage words and the helper's path-free reason token only.
    var reportWord: String {
        switch self {
        case .passed: return "passed"
        case .stopped(let stage, let reason): return "stopped at \(stage.rawValue)" + (reason.isEmpty ? "" : " (\(reason))")
        }
    }
}

struct JitStep {
    let row: Int
    let advice: String?
    let primary: JitPrimary?
}

// The first condition that holds wins; it never offers a way to continue without JIT.
enum JitNextStep {
    static let severity = String(localized: "ARMSX3 cannot run games without JIT. Without it, games do not start at all.")
    static let setupSuffix = String(localized: "Setup continues here.")

    private static let row2 = String(localized: "Turn on get-task-allow when you sign ARMSX3. No JIT method works without it.")
    private static let row4 = String(localized: "Import the pairing file made for this device.")
    private static let row4SideStore = String(localized: "Get the pairing file from SideStore. ARMSX3 opens SideStore, which sends the file back.")
    private static let row4NoAnswer = String(localized: "SideStore didn't send a pairing file. Update SideStore and try again, or import the file from Files.")
    private static let row4Lockdown = String(localized: "SideStore sent an older lockdown pairing file.") + " "
        + String(localized: "In SideStore, open Pairing File Management, long-press Remote Pairing File and choose Set as Preferred, then try again.")
    private static let row5 = String(localized: "Mount the Developer Disk Image. The first mount downloads about 16 MB.")
    private static let row7 = String(localized: "Close ARMSX3 from the app switcher and open it again. JIT turns on as it opens.")
    private static let row9 = String(localized: "StikDebug didn't turn JIT on. In StikDebug, check that LocalDevVPN is on and a pairing file is imported, then try again.")
    // A plain Mac attach does not run the JIT script on a TXM device, so this clause needs TXM known absent.
    private static let row11Mac = String(localized: "ARMSX3 waits for a debugger as it opens. Close ARMSX3, then open it from StikDebug or attach a debugger from your Mac.")
    private static let row11TXM = String(localized: "ARMSX3 waits for a debugger as it opens. Close ARMSX3, then open it from StikDebug.")
    private static let mountOther = String(localized: "Mounting didn't finish. Try again. If it keeps failing, restart this device.")

    private static let stikDebugReady = String(localized: "StikDebug can turn on JIT for ARMSX3 this time.")
    private static let stikDebugInstead = String(localized: "If built-in JIT can't work here, StikDebug can turn it on instead.")

    private static let diskImageUnknownFix = String(localized: "Test JIT Setup checks it.")

    static func diskImageFix(jitOn: Bool, diskImage: DiskImageState, testRowShown: Bool) -> String? {
        !jitOn && testRowShown && diskImage == .unknown ? diskImageUnknownFix : nil
    }

    /// The stage word shown while the test runs; a word the helper adds later keeps the first one.
    static func testStageValue(_ stage: String?) -> String {
        switch stage {
        case "connecting"?: return String(localized: "Connecting…")
        case "pairing"?: return String(localized: "Pairing…")
        default: return String(localized: "Starting helper…")
        }
    }

    /// Closes the advice of a step where built-in JIT cannot start now; the primary is StikDebug when it is installed.
    private static func fallback(_ s: JitSituation, after sentence: String?) -> (advice: String, primary: JitPrimary) {
        let line = s.stikDebugInstalled ? stikDebugReady : stikDebugInstead
        return (sentence.map { $0 + " " + line } ?? line, s.stikDebugInstalled ? .openStikDebug : .tryAgain)
    }

    static func decide(_ s: JitSituation) -> JitStep {
        if s.jitOn {
            guard let advice = s.fallbackAdvice else { return JitStep(row: 1, advice: nil, primary: nil) }
            return JitStep(row: 12, advice: advice, primary: nil)
        }
        if s.entitlementMissing { return JitStep(row: 2, advice: row2, primary: nil) }
        if s.stikDebugReturnedOff { return JitStep(row: 9, advice: row9, primary: .openStikDebug) }
        if s.checklistFails {
            let next = fallback(s, after: nil)
            // Trying again cannot help until LiveContainer is updated.
            return JitStep(row: 3, advice: next.advice, primary: s.liveContainerOld && next.primary == .tryAgain ? nil : next.primary)
        }
        switch s.method {
        case .builtin, .stikdebug:
            if !s.pairingImported || s.importRefusal != nil {
                return pairingStep(s)
            }
            // A stop at the disk image is stale once a mount succeeded.
            let testFoundNoImage = s.testResult?.stoppedStage == .diskImage && s.diskImage != .mounted
            switch s.diskImage {
            case .notMounted, .busy:
                let failed = s.mountResult.flatMap { $0 == "ready" ? nil : mountSentence($0) }
                // The test row already says the image is not mounted, so row5 would only repeat it.
                let repeatsTest = testFoundNoImage && s.diskImage == .notMounted
                return JitStep(row: 5, advice: failed ?? (repeatsTest ? nil : row5), primary: .mountDiskImage)
            case .mounted, .unknown:
                if testFoundNoImage { return JitStep(row: 5, advice: nil, primary: .mountDiskImage) }
            }
            // Nothing is left to press: the next launch turns JIT on, so no StikDebug line either.
            if s.testResult == .passed { return JitStep(row: 7, advice: row7, primary: nil) }
            if let stage = s.testResult?.stoppedStage, stage != .diskImage, !s.actedThisLaunch {
                let next = fallback(s, after: nil)
                return JitStep(row: 3, advice: next.advice, primary: next.primary)
            }
            let word = s.liveProcessMissing ? "liveprocess-missing" : s.helperWord
            if !s.actedThisLaunch, let sentence = helperSentence(word) {
                let next = fallback(s, after: sentence)
                return JitStep(row: 6, advice: next.advice, primary: next.primary)
            }
            let next = fallback(s, after: row7)
            return JitStep(row: 7, advice: next.advice, primary: next.primary)
        case .wait:
            return JitStep(row: 11, advice: s.txm == .missing ? row11Mac : row11TXM, primary: nil)
        }
    }

    /// A refused import is answered first; after that the likeliest source for this install.
    private static func pairingStep(_ s: JitSituation) -> JitStep {
        if let refusal = s.importRefusal { return JitStep(row: 4, advice: refusal, primary: .importPairingFile) }
        if s.sideStoreLockdown { return JitStep(row: 4, advice: row4Lockdown, primary: .getFromSideStore) }
        if s.sideStoreSentNothing { return JitStep(row: 4, advice: row4NoAnswer, primary: .getFromSideStore) }
        if s.liveContainer {
            let place = s.isPad ? String(localized: "On My iPad") : String(localized: "On My iPhone")
            return JitStep(row: 4, advice: row4 + " " + String(localized: "If SideStore is installed, it keeps one in Files under \(place) › SideStore."),
                           primary: .importPairingFile)
        }
        if s.sideStoreAvailable { return JitStep(row: 4, advice: row4SideStore, primary: .getFromSideStore) }
        return JitStep(row: 4, advice: row4, primary: .importPairingFile)
    }

    /// The share line is left out inside LiveContainer, where a shared file may not reach the guest.
    static func otherWays(liveContainer: Bool, sideStoreAvailable: Bool) -> [String] {
        let iLoader = String(localized: "On a computer, iLoader can make a pairing file for this device. Save it to Files, then import it here.")
        if liveContainer { return [iLoader] }
        return [String(localized: "Share the file to ARMSX3 from Files or another app."),
                String(localized: "Or put a file named pairingFile.plist in ARMSX3's folder in Files, then open ARMSX3 again."),
                iLoader]
    }

    /// Nil when the helper's word adds nothing.
    static func helperSentence(_ word: String) -> String? {
        switch word {
        case "connect-failed":
            return String(localized: "ARMSX3 couldn't reach this device through LocalDevVPN. Turn LocalDevVPN on and stay on Wi-Fi, or turn on Airplane mode, then reopen ARMSX3.")
        case "tunnel-failed":
            return String(localized: "LocalDevVPN is on, but this device didn't accept the pairing. Make a new pairing file on a computer, import it here, then reopen ARMSX3.")
        case "pairing-rejected":
            return String(localized: "Your pairing file couldn't be read. Make a new pairing file on a computer and import it.")
        case "ddi-not-mounted":
            return String(localized: "The Developer Disk Image isn't mounted. Mount it here, then reopen ARMSX3.")
        case "liveprocess-missing":
            return String(localized: "LiveContainer's LiveProcess extension is missing. Reinstall LiveContainer and keep its extensions.")
        case "attach-failed", "prepare-incomplete", "detach-failed", "timeout", "interrupted", "start-failed", "invalid-request",
             "connect-timeout":
            return String(localized: "JIT setup didn't finish. Close ARMSX3 fully and open it again.")
        default:
            return nil
        }
    }

    static func mountSentence(_ result: String) -> String? {
        switch result {
        case "": return nil
        case "ready": return row7
        case "unreachable":
            return String(localized: "Couldn't reach this device through LocalDevVPN. Turn LocalDevVPN on and stay on Wi-Fi, or turn on Airplane mode, then try again.")
        case "download": return String(localized: "The disk image didn't download. Check the internet connection, then try again.")
        case "pairing": return String(localized: "This device didn't accept the pairing file. Make a new pairing file on a computer and import it.")
        case "mount":
            return String(localized: "The disk image didn't mount. Restart this device, then try again. If it keeps failing, use Reset Developer Disk Image.")
        default: return mountOther
        }
    }

    static func importRefusalSentence(_ outcome: Int) -> String? {
        switch outcome {
        case 1: return String(localized: "That file couldn't be imported. Try picking it again.")
        case 2: return String(localized: "This is an older lockdown pairing file. ARMSX3 needs an RPPairing file made for this device.")
        case 3: return String(localized: "That file isn't a pairing file. Pick the pairing file made for this device.")
        default: return nil
        }
    }

    /// A mount reason from this launch, else the helper's word.
    static func codeLine(reason: String, helperWord: String, mountReason: String?) -> String {
        let detail = mountReason.flatMap { $0.isEmpty ? nil : $0 } ?? helperWord
        return detail.isEmpty ? String(localized: "Code: \(reason)") : String(localized: "Code: \(reason) · \(detail)")
    }
}
