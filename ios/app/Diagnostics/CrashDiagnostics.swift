// SPDX-License-Identifier: GPL-2.0-only
// MetricKit crash payloads, saved on delivery because pastDiagnosticPayloads covers this app instance only.

import Foundation
import MetricKit

final class CrashDiagnostics: NSObject, MXMetricManagerSubscriber {
    static let shared = CrashDiagnostics()

    private let lock = NSLock()
    private var started = false

    private override init() {}

    // Adds the subscriber once per process. Apple documents adding it during launch as safe.
    func start() {
        lock.lock()
        defer { lock.unlock() }
        guard !started else { return }
        started = true
        MXMetricManager.shared.add(self)
    }

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        let store = DiagnosticsStore.live()
        lock.lock()
        for payload in payloads {
            do {
                try store.persistPayload(payload.jsonRepresentation(), ending: payload.timeStampEnd)
            } catch {}
        }
        lock.unlock()
    }
}
