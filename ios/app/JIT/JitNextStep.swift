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

    /// Nil reads "Not set": Built-in stored under LiveContainer, where it is hidden.
    static func effective(stored: JitMethod, liveContainer: Bool) -> JitMethod? {
        stored == .builtin && liveContainer ? nil : stored
    }

    var title: String {
        switch self {
        case .builtin: return String(localized: "Built-in")
        case .stikdebug: return String(localized: "StikDebug")
        case .wait: return String(localized: "Wait for Debugger")
        }
    }

    var caption: String {
        switch self {
        case .builtin: return String(localized: "ARMSX3 turns on JIT by itself each time it opens. Needs a pairing file and LocalDevVPN.")
        case .stikdebug: return String(localized: "ARMSX3 asks the StikDebug app to turn on JIT. Needs StikDebug installed.")
        case .wait: return String(localized: "ARMSX3 waits for a debugger you attach yourself, from StikDebug or a Mac.")
        }
    }
}

// No pid is sent: StikDebug relaunches the app traced, and TXM unknown counts as present.
enum StikDebugLink {
    static func url(bundleID: String, txm: Presence, forceScript: Bool) -> URL? {
        var components = URLComponents()
        components.scheme = "stikdebug"
        components.host = "enable-jit"
        var items = [URLQueryItem(name: "bundle-id", value: bundleID)]
        if txm != .missing || forceScript {
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
    case chooseMethod, importPairingFile, mountDiskImage, openStikDebug

    var title: String {
        switch self {
        case .chooseMethod: return String(localized: "Choose Method")
        case .importPairingFile: return String(localized: "Import Pairing File")
        case .mountDiskImage: return String(localized: "Mount Disk Image")
        case .openStikDebug: return String(localized: "Open StikDebug")
        }
    }
}

// `jitOn` is the process's own launch verdict; an opened StikDebug link never sets it.
struct JitSituation {
    var jitOn = false
    var entitlementMissing = false
    var method: JitMethod? = .builtin
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
    static let liveContainerSentence = String(localized: "In LiveContainer's settings, turn on Use LiveContainer's Bundle ID.")

    private static let row2 = String(localized: "Turn on get-task-allow when you sign ARMSX3. No JIT method works without it.")
    private static let row3 = String(localized: "Built-in JIT isn't available inside LiveContainer. Choose StikDebug or Wait for Debugger.")
    private static let row4 = String(localized: "Import the pairing file made for this device.")
    private static let row5 = String(localized: "Mount the Developer Disk Image. The first mount downloads about 16 MB.")
    private static let row7 = String(localized: "Close ARMSX3 from the app switcher and open it again. JIT turns on as it opens.")
    private static let row8 = String(localized: "Install StikDebug, then come back here.")
    private static let row9 = String(localized: "StikDebug didn't turn JIT on. In StikDebug, check that LocalDevVPN is on and a pairing file is imported, then try again.")
    private static let row10 = String(localized: "StikDebug turns on JIT for ARMSX3 and sends you back here.")
    // A plain Mac attach does not run the JIT script on a TXM device, so this clause needs TXM known absent.
    private static let row11Mac = String(localized: "ARMSX3 waits for a debugger as it opens. Close ARMSX3, then open it from StikDebug or attach a debugger from your Mac.")
    private static let row11TXM = String(localized: "ARMSX3 waits for a debugger as it opens. Close ARMSX3, then open it from StikDebug.")
    private static let mountOther = String(localized: "Mounting didn't finish. Try again. If it keeps failing, restart this device.")

    static func decide(_ s: JitSituation) -> JitStep {
        if s.jitOn { return JitStep(row: 1, advice: nil, primary: nil) }
        if s.entitlementMissing { return JitStep(row: 2, advice: row2, primary: nil) }
        guard let method = s.method else { return JitStep(row: 3, advice: row3, primary: .chooseMethod) }
        switch method {
        case .builtin:
            if !s.pairingImported || s.importRefusal != nil {
                return JitStep(row: 4, advice: s.importRefusal ?? row4, primary: .importPairingFile)
            }
            switch s.diskImage {
            case .notMounted, .busy:
                let failed = s.mountResult.flatMap { $0 == "ready" ? nil : mountSentence($0) }
                return JitStep(row: 5, advice: failed ?? row5, primary: .mountDiskImage)
            case .mounted, .unknown:
                break
            }
            if !s.actedThisLaunch, let sentence = helperSentence(s.helperWord) {
                return JitStep(row: 6, advice: sentence, primary: nil)
            }
            return JitStep(row: 7, advice: row7, primary: nil)
        case .stikdebug:
            if !s.stikDebugInstalled { return JitStep(row: 8, advice: row8, primary: nil) }
            let row = s.stikDebugReturnedOff ? 9 : 10
            let advice = s.stikDebugReturnedOff ? row9 : row10
            return JitStep(row: row, advice: s.liveContainer ? advice + " " + liveContainerSentence : advice, primary: .openStikDebug)
        case .wait:
            return JitStep(row: 11, advice: s.txm == .missing ? row11Mac : row11TXM, primary: nil)
        }
    }

    /// Nil when the helper's word adds nothing.
    static func helperSentence(_ word: String) -> String? {
        switch word {
        case "connect-failed":
            return String(localized: "ARMSX3 couldn't reach this device through LocalDevVPN. Turn LocalDevVPN on and stay on Wi-Fi, or turn on Airplane mode, then reopen ARMSX3.")
        case "tunnel-failed":
            return String(localized: "LocalDevVPN is on, but this device didn't accept the pairing. Make a new pairing file on your Mac, import it here, then reopen ARMSX3.")
        case "pairing-rejected":
            return String(localized: "Your pairing file couldn't be read. Make a new pairing file on your Mac and import it.")
        case "ddi-not-mounted":
            return String(localized: "The Developer Disk Image isn't mounted. Mount it here, then reopen ARMSX3.")
        case "attach-failed", "prepare-incomplete", "detach-failed", "timeout", "interrupted", "start-failed", "invalid-request":
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
        case "pairing": return String(localized: "This device didn't accept the pairing file. Make a new pairing file on your Mac and import it.")
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
