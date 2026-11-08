// SPDX-License-Identifier: GPL-2.0-only
// The JIT helper's pure decisions, with no idevice and no I/O, so a host test compiles and runs them.

import Foundation

enum DDIAnswer: String {
    case mounted
    case notMounted = "not-mounted"
    case unknown
}

enum HelperDecisions {
    /// The reachability probe's bound: with LocalDevVPN off nothing answers, so this is what the VPN-off path costs.
    static let probeTimeout: TimeInterval = 1.5

    /// The one tunnel retry starts only before this point, since a retry costs idevice's 5 s connect and the launch needs the rest.
    static let retryCutoffMs: Int64 = 2500

    static func shouldRetryTunnel(elapsedMs: Int64) -> Bool {
        elapsedMs <= retryCutoffMs
    }

    /// Mounted when RSD lists the debug proxy, else the legacy mounter's answer, else unknown, which goes on to attach.
    static func ddiVerdict(debugProxyListed: Bool?, legacy: Bool?) -> DDIAnswer {
        if let listed = debugProxyListed { return listed ? .mounted : .notMounted }
        if let legacy { return legacy ? .mounted : .notMounted }
        return .unknown
    }

    // MARK: - the mount result and its reason token

    /// The UI's result class for a mount attempt, by the first rule that holds; errorKind is this port's word, never StikJIT's text.
    static func mountResult(probeFailed: Bool, failedStep: MountStep?, errorKind: String) -> MountResult {
        if probeFailed { return .unreachable }
        guard let step = failedStep else { return .ready }
        if errorKind == "pairing-file" { return .pairing }
        if step == .downloading || errorKind == "ddi-download" { return .download }
        if step == .checking && errorKind == "device" { return .pairing }
        if step == .mounting || step == .verifying { return .mount }
        return .other
    }

    /// `<step or probe>:<kind>[:<code>/<subCode>]`, lower case, only [a-z0-9:/-], at most 40 characters; a nil subCode reads 0.
    static func reasonToken(step: MountStep?, kind: String, code: Int32?, subCode: Int32?) -> String {
        let kindWord = kind.lowercased().filter { ("a"..."z").contains($0) || ("0"..."9").contains($0) || $0 == "-" }
        var token = "\(step?.rawValue ?? "probe"):\(kindWord)"
        if let code { token += ":\(code)/\(subCode ?? 0)" }
        let safe = token.filter { ("a"..."z").contains($0) || ("0"..."9").contains($0) || ":/-".contains($0) }
        return String(safe.prefix(40))
    }
}

/// The step a mount attempt failed at, in StikJIT's prepareDevice order.
enum MountStep: String {
    case checking, downloading, mounting, verifying
}

/// The JIT pane's result classes for a mount attempt.
enum MountResult: String {
    case ready, unreachable, download, pairing, mount, other
}
