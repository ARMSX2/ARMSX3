// SPDX-License-Identifier: GPL-2.0-only
// Mounts the Developer Disk Image when the player taps Mount, in StikJIT's prepareDevice order; reasons never carry a path.

import Foundation

// The one StikJIT type the vendored DDISession names; the values are StikJIT's Configuration defaults.
enum StikJIT {
    struct Configuration {
        var deviceAddress = "10.7.0.1"
        var rsdPort: UInt16 = 49152
        var connectionTimeout: TimeInterval = 5
    }
}

enum DDIMount {
    /// One mount attempt, reporting connecting, downloading (only when needed), mounting and checking; it blocks, so run it on the DDI queue.
    static func run(pairingPath: String, paths: DDIPaths, stage: (String) -> Void) -> (result: MountResult, reason: String) {
        let configuration = StikJIT.Configuration()
        stage("connecting")
        if EndpointProbe.failureReason(address: configuration.deviceAddress, port: configuration.rsdPort,
                                       timeout: configuration.connectionTimeout) != nil {
            return (.unreachable, HelperDecisions.reasonToken(step: nil, kind: "probe", code: nil, subCode: nil))
        }

        var step = MountStep.checking
        do {
            let session = DDISession(pairingFilePath: pairingPath, configuration: configuration)
            if try session.isMounted() { return (.ready, "none") }
            if !paths.allFilesUsable {
                step = .downloading
                stage("downloading")
                try SynchronousDDIDownloader.download(to: paths) { _, _ in }
            }
            step = .mounting
            stage("mounting")
            // The UI shows stages, not bytes, so the progress fraction is ignored.
            try session.mountDDI(paths: paths) { _ in }
            step = .verifying
            stage("checking")
            guard try session.isMounted() else { throw StikJITError.ddiNotMounted }
            return (.ready, "none")
        } catch {
            let failure = kind(of: error)
            return (HelperDecisions.mountResult(probeFailed: false, failedStep: step, errorKind: failure.kind),
                    HelperDecisions.reasonToken(step: step, kind: failure.kind, code: failure.code, subCode: failure.subCode))
        }
    }

    /// Asks only whether the image is mounted; never downloads or mounts. It blocks, so run it on the DDI queue.
    static func check(pairingPath: String, stage: (String) -> Void) -> (result: MountResult, reason: String) {
        let configuration = StikJIT.Configuration()
        stage("connecting")
        if EndpointProbe.failureReason(address: configuration.deviceAddress, port: configuration.rsdPort,
                                       timeout: configuration.connectionTimeout) != nil {
            return (.unreachable, HelperDecisions.reasonToken(step: nil, kind: "probe", code: nil, subCode: nil))
        }
        stage("pairing")
        do {
            let mounted = try DDISession(pairingFilePath: pairingPath, configuration: configuration).isMounted()
            return (mounted ? .ready : .unmounted, "none")
        } catch {
            let failure = kind(of: error)
            return (HelperDecisions.mountResult(probeFailed: false, failedStep: .checking, errorKind: failure.kind),
                    HelperDecisions.reasonToken(step: .checking, kind: failure.kind, code: failure.code, subCode: failure.subCode))
        }
    }

    /// This port's word for a StikJIT error case; only the case and its two numbers are read, never its text.
    private static func kind(of error: Error) -> (kind: String, code: Int32?, subCode: Int32?) {
        guard let error = error as? StikJITError else { return ("other", nil, nil) }
        switch error {
        case .pairingFile: return ("pairing-file", nil, nil)
        case .debugProxyUnavailable: return ("debug-proxy", nil, nil)
        case .device(let code, let subCode, _): return ("device", code, subCode)
        case .ddiDownload: return ("ddi-download", nil, nil)
        case .ddiFilesMissing: return ("ddi-files-missing", nil, nil)
        case .ddiNotMounted: return ("ddi-not-mounted", nil, nil)
        default: return ("other", nil, nil)
        }
    }
}
